package de.caritas.cob.userservice.api.adapters.web.controller;

import de.caritas.cob.userservice.api.service.statistics.SessionStatisticsService;
import de.caritas.cob.userservice.api.statistics.model.SessionStatisticsResultDTO;
import de.caritas.cob.userservice.generated.api.statistics.controller.UserstatisticsApi;
import io.swagger.annotations.Api;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Controller to handle all statistics requests. */
@RestController
@RequiredArgsConstructor
@Api(tags = "user-statistics-controller")
public class UserStatisticsController implements UserstatisticsApi {

  private final @NonNull SessionStatisticsService sessionStatisticsService;

  /**
   * Retrieve a session via session ID or Matrix room ID.
   *
   * @param sessionId The id of the session.
   * @param matrixRoomId Matrix room ID of the session. If the session ID is also passed, the query
   *     uses it.
   * @return a {@link SessionStatisticsResultDTO} instance
   */
  @Override
  public ResponseEntity<SessionStatisticsResultDTO> getSession(
      Long sessionId, String matrixRoomId) {
    return new ResponseEntity<>(
        sessionStatisticsService.retrieveSession(sessionId, matrixRoomId), HttpStatus.OK);
  }
}
