package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.DeletedAskerSession;
import de.caritas.cob.userservice.api.service.session.DeletedAskerSessionService;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DeletedAskerSessionControllerTest {

  @InjectMocks private DeletedAskerSessionController underTest;
  @Mock private DeletedAskerSessionService service;
  @Mock private AuthenticatedUser authenticatedUser;

  @Test
  void returnsTheCallingCounsellorsPlaceholdersInUtc() {
    when(authenticatedUser.getUserId()).thenReturn("consultant-1");
    when(service.findForConsultant("consultant-1"))
        .thenReturn(
            List.of(
                DeletedAskerSession.builder()
                    .consultantId("consultant-1")
                    .formerSessionId(77L)
                    .agencyId(5L)
                    .deletedAt(LocalDateTime.of(2026, 10, 29, 9, 30))
                    .build()));

    var body = underTest.getDeletedAskerSessions().getBody();

    assertThat(body).hasSize(1);
    assertThat(body.get(0).getFormerSessionId()).isEqualTo(77L);
    assertThat(body.get(0).getAgencyId()).isEqualTo(5L);
    assertThat(body.get(0).getDeletedAt().getOffset()).isEqualTo(ZoneOffset.UTC);
    assertThat(body.get(0).getDeletedAt().getHour()).isEqualTo(9);
  }
}
