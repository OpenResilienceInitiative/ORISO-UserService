package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.web.dto.ConsultantLiveAvailabilityRequestDTO;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.service.availability.ConsultantActivityRegistry;
import java.util.Collections;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ConsultantLiveAvailabilityControllerTest {

  @Mock private AuthenticatedUser authenticatedUser;
  @Mock private ConsultantActivityRegistry consultantActivityRegistry;

  @InjectMocks private ConsultantLiveAvailabilityController controller;

  @BeforeEach
  void setUp() {
    ReflectionTestUtils.setField(controller, "activeWindowMs", 120_000L);
  }

  @Test
  void setLiveChatAvailability_consultantAndAvailableTrue_marksAvailable() {
    // Business reason: enabling live chat must immediately make consultant routable for new chats.
    when(authenticatedUser.isConsultant()).thenReturn(true);
    when(authenticatedUser.getUserId()).thenReturn("consultant-1");
    var request = new ConsultantLiveAvailabilityRequestDTO(true);

    var response = controller.setLiveChatAvailability(request);

    assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
    verify(consultantActivityRegistry).markAvailable("consultant-1");
    verify(consultantActivityRegistry, never()).markUnavailable("consultant-1");
  }

  @Test
  void setLiveChatAvailability_consultantAndAvailableFalse_marksUnavailable() {
    // Business reason: disabling live chat must remove consultant from active routing immediately.
    when(authenticatedUser.isConsultant()).thenReturn(true);
    when(authenticatedUser.getUserId()).thenReturn("consultant-2");
    var request = new ConsultantLiveAvailabilityRequestDTO(false);

    var response = controller.setLiveChatAvailability(request);

    assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
    verify(consultantActivityRegistry).markUnavailable("consultant-2");
  }

  @Test
  void setLiveChatAvailability_consultantAndAvailableNull_marksUnavailable() {
    // Business reason: null availability payload should fail safe to unavailable to avoid stale
    // live
    // presence.
    when(authenticatedUser.isConsultant()).thenReturn(true);
    when(authenticatedUser.getUserId()).thenReturn("consultant-3");
    var request = new ConsultantLiveAvailabilityRequestDTO(null);

    var response = controller.setLiveChatAvailability(request);

    assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
    verify(consultantActivityRegistry).markUnavailable("consultant-3");
  }

  @Test
  void setLiveChatAvailability_nonConsultantWithUserId_doesNotMutateRegistry() {
    // Business reason: only consultants are allowed to update consultant availability state.
    when(authenticatedUser.isConsultant()).thenReturn(false);
    var request = new ConsultantLiveAvailabilityRequestDTO(true);

    var response = controller.setLiveChatAvailability(request);

    assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
    verifyNoInteractions(consultantActivityRegistry);
  }

  @Test
  void setLiveChatAvailability_consultantWithNullUserId_doesNotMutateRegistryAndDoesNotThrow() {
    // Business reason: missing identity metadata must never crash status updates in production.
    when(authenticatedUser.isConsultant()).thenReturn(true);
    when(authenticatedUser.getUserId()).thenReturn(null);
    var request = new ConsultantLiveAvailabilityRequestDTO(true);

    var response = controller.setLiveChatAvailability(request);

    assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
    verifyNoInteractions(consultantActivityRegistry);
  }

  @Test
  void getLiveChatAvailability_consultantWithNullUserId_returnsFalseWithoutRegistryQuery() {
    // Business reason: unknown consultant identity must not accidentally read or mutate
    // availability.
    when(authenticatedUser.isConsultant()).thenReturn(true);
    when(authenticatedUser.getUserId()).thenReturn(null);

    var response = controller.getLiveChatAvailability();

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertEquals(false, response.getBody().getAvailable());
    verifyNoInteractions(consultantActivityRegistry);
  }

  @Test
  void getLiveChatAvailability_consultantOutsideActiveSet_returnsFalse() {
    // Business reason: availability indicator must show false when consultant is outside active
    // window.
    when(authenticatedUser.isConsultant()).thenReturn(true);
    when(authenticatedUser.getUserId()).thenReturn("consultant-4");
    when(consultantActivityRegistry.filterActive(anyCollection(), anyLong()))
        .thenReturn(Collections.emptySet());

    var response = controller.getLiveChatAvailability();

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertEquals(false, response.getBody().getAvailable());
  }
}
