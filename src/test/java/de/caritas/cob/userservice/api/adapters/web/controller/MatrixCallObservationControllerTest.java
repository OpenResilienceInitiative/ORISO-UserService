package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.service.matrix.MatrixCallGuestService;
import de.caritas.cob.userservice.api.service.matrix.MatrixCallObservationService;
import de.caritas.cob.userservice.api.service.matrix.MatrixCallStateService;
import de.caritas.cob.userservice.api.service.matrix.MatrixSessionCallBindingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Error mapping of the call-observation endpoints (#1624). */
class MatrixCallObservationControllerTest {
  private static final String REGISTER =
      "{\"sourceRoomId\":\"!source:example\",\"callId\":\"call\",\"callRoomId\":\"!media:example\","
          + "\"video\":true}";
  private static final String END = "{\"sourceRoomId\":\"!source:example\",\"callId\":\"call\"}";

  private final MatrixCallObservationService observations =
      mock(MatrixCallObservationService.class);
  private final MatrixSessionCallBindingService sessionBindings =
      mock(MatrixSessionCallBindingService.class);
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    mvc =
        MockMvcBuilders.standaloneSetup(
                new MatrixCallStateController(
                    mock(MatrixCallStateService.class),
                    mock(MatrixCallGuestService.class),
                    observations,
                    sessionBindings,
                    new AuthenticatedUser()))
            .build();
  }

  private ResultActions register() throws Exception {
    return mvc.perform(
        post("/service/matrix/calls/observations")
            .contentType(MediaType.APPLICATION_JSON)
            .content(REGISTER));
  }

  private void registerThrows(RuntimeException failure) {
    when(observations.register(any(), anyString(), anyString(), anyString(), anyBoolean()))
        .thenThrow(failure);
  }

  @Test
  void registrationRaceOnTheSourceCallConstraintIsAConflict() throws Exception {
    registerThrows(
        new DuplicateKeyException(
            "Duplicate entry 'a-b' for key"
                + " 'matrix_call_observation.uk_call_observation_source_call'"));

    register()
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.reason").value("call_observation_conflict"));
  }

  @Test
  void registrationRaceOnTheMediaRoomConstraintIsAConflict() throws Exception {
    registerThrows(
        new DataIntegrityViolationException(
            "could not execute statement",
            new java.sql.SQLIntegrityConstraintViolationException(
                "Duplicate entry '!m' for key 'uk_call_observation_media_room'")));

    register().andExpect(status().isConflict());
  }

  @Test
  void lockTimeoutOnRegistrationIsAConflict() throws Exception {
    registerThrows(new CannotAcquireLockException("lock wait timeout"));

    register().andExpect(status().isConflict());
  }

  @Test
  void deadlockBetweenTwoFirstRegistrationsIsAConflict() throws Exception {
    registerThrows(new org.springframework.dao.DeadlockLoserDataAccessException("deadlock", null));

    register().andExpect(status().isConflict());
  }

  @Test
  void lockFailureWhileEndingIsAConflictNotAServerError() throws Exception {
    when(observations.end(any(), anyString(), anyString()))
        .thenThrow(new CannotAcquireLockException("lock wait timeout"));

    mvc.perform(
            post("/service/matrix/calls/observations/end")
                .contentType(MediaType.APPLICATION_JSON)
                .content(END))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.reason").value("call_observation_conflict"));
  }

  @Test
  void otherIntegrityViolationsAreNotDisguisedAsARetryableConflict() {
    registerThrows(
        new DataIntegrityViolationException(
            "could not execute statement",
            new java.sql.SQLIntegrityConstraintViolationException(
                "Column 'creator_matrix_id' cannot be null")));

    assertThatThrownBy(this::register)
        .hasRootCauseInstanceOf(java.sql.SQLIntegrityConstraintViolationException.class);
  }

  @Test
  void registrationWhileMatrixIsUnavailableIsABadGateway() throws Exception {
    registerThrows(new MatrixSynapseService.CallLookupUnavailableException("down"));

    register()
        .andExpect(status().isBadGateway())
        .andExpect(jsonPath("$.reason").value("call_state_lookup_unavailable"));
  }

  @Test
  void endingWhileMatrixIsUnavailableIsABadGateway() throws Exception {
    when(observations.end(any(), anyString(), anyString()))
        .thenThrow(new MatrixSynapseService.CallLookupUnavailableException("down"));

    mvc.perform(
            post("/service/matrix/calls/observations/end")
                .contentType(MediaType.APPLICATION_JSON)
                .content(END))
        .andExpect(status().isBadGateway())
        .andExpect(jsonPath("$.reason").value("call_state_lookup_unavailable"));
  }

  @Test
  void rejectedRegistrationIsNotFound() throws Exception {
    when(observations.register(any(), anyString(), anyString(), anyString(), anyBoolean()))
        .thenReturn(MatrixCallObservationService.Outcome.NOT_FOUND);

    register().andExpect(status().isNotFound());
  }

  @Test
  void acceptedRegistrationIsNoContent() throws Exception {
    when(observations.register(any(), anyString(), anyString(), anyString(), anyBoolean()))
        .thenReturn(MatrixCallObservationService.Outcome.OK);

    register().andExpect(status().isNoContent());
  }

  @Test
  void theSessionCounsellorsVerifiedRegistrationAlsoBindsTheCall() throws Exception {
    // FE#2209: share links, knocks and seats read bindings only.
    when(observations.register(any(), anyString(), anyString(), anyString(), anyBoolean()))
        .thenReturn(MatrixCallObservationService.Outcome.OK);
    when(sessionBindings.bind(any(), anyString(), anyString(), anyString(), anyBoolean()))
        .thenReturn(true);

    register().andExpect(status().isNoContent());
    verify(sessionBindings)
        .bind(any(), eq("!source:example"), eq("call"), eq("!media:example"), eq(true));
  }

  @Test
  void aRenewalOfABoundSessionCallStaysAccepted() throws Exception {
    // Once bound, the observation path steps aside (NOT_FOUND); the binding confirms the call.
    when(observations.register(any(), anyString(), anyString(), anyString(), anyBoolean()))
        .thenReturn(MatrixCallObservationService.Outcome.NOT_FOUND);
    when(sessionBindings.bind(any(), anyString(), anyString(), anyString(), anyBoolean()))
        .thenReturn(true);

    register().andExpect(status().isNoContent());
  }
}
