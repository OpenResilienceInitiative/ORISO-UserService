package de.caritas.cob.userservice.api.adapters.web.controller;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Cluster-internal admission only. A call join never renews the personal inactivity clock. */
@RestController
public class AccountInactivityMediaController {
  private final JdbcTemplate jdbc;
  private final boolean enabled;
  private final byte[] token;

  public AccountInactivityMediaController(
      JdbcTemplate jdbc,
      @Value("${matrixrtc.lifecycle.enabled:false}") boolean enabled,
      @Value("${matrixrtc.lifecycle.token:}") String token) {
    this.jdbc = jdbc;
    this.enabled = enabled;
    this.token = token.getBytes(StandardCharsets.UTF_8);
  }

  public record Request(String matrixUserId) {}

  @PostMapping("/internal/matrixrtc/media-access")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void admit(
      @RequestHeader(value = "x-matrixrtc-lifecycle-token", required = false) String supplied,
      @RequestBody Request request) {
    if (token.length < 32
        || supplied == null
        || !MessageDigest.isEqual(token, supplied.getBytes(StandardCharsets.UTF_8)))
      throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    if (!enabled) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
    if (request.matrixUserId() == null
        || request.matrixUserId().isBlank()
        || request.matrixUserId().length() > 255)
      throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    try {
      var identities =
          jdbc.queryForList(
              "SELECT user_id FROM user WHERE matrix_user_id=? UNION SELECT consultant_id FROM consultant WHERE matrix_user_id=?",
              String.class,
              request.matrixUserId(),
              request.matrixUserId());
      if (identities.isEmpty()) throw new ResponseStatusException(HttpStatus.GONE);
      if (identities.size() != 1) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
      var states =
          jdbc.queryForList(
              "SELECT status FROM account_inactivity WHERE identity_id=?",
              String.class,
              identities.getFirst());
      if (states.size() == 1 && "DELETED".equals(states.getFirst()))
        throw new ResponseStatusException(HttpStatus.GONE);
      if (states.size() != 1 || !"ACTIVE".equals(states.getFirst()))
        throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    } catch (org.springframework.dao.DataAccessException failure) {
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
    }
  }
}
