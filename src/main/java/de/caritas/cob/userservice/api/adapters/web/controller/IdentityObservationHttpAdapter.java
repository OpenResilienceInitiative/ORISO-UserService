package de.caritas.cob.userservice.api.adapters.web.controller;

import de.caritas.cob.userservice.api.adapters.web.dto.*;
import de.caritas.cob.userservice.api.helper.UsernameTranscoder;
import de.caritas.cob.userservice.api.service.identity.observation.*;
import java.time.ZoneOffset;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/** HTTP translation only; the generated route is registered once by UserAdminController. */
@Component
@RequiredArgsConstructor
@Slf4j
public class IdentityObservationHttpAdapter {
  static final String INVALID_SELECTOR_MESSAGE =
      "Provide at least one valid exact identityId, username or email; blank, overlong, control, wildcard and malformed encoded values are not allowed.";
  private final IdentityObservationService service;
  private final UsernameTranscoder transcoder;

  public ResponseEntity<IdentityObservationResponseDTO> observeIdentityOccupancy(
      IdentityObservationRequestDTO request) {
    try {
      service.requirePermission();
      var probe = parseSelectors(request);
      var observation = service.observe(probe);
      var dto =
          new SafeResponse()
              .scope(IdentityObservationResponseDTO.ScopeEnum.IDENTITY_OCCUPANCY_V1)
              .observedAt(observation.observedAt().atOffset(ZoneOffset.UTC))
              .normalizedSelectors(
                  new IdentityObservationSelectorsDTO()
                      .identityId(probe.identityId())
                      .usernames(probe.usernames())
                      .email(probe.email()))
              .identityScanComplete(observation.complete())
              .occupancy(
                  IdentityObservationResponseDTO.OccupancyEnum.fromValue(observation.occupancy()))
              .layers(
                  observation.layers().stream()
                      .map(
                          layer ->
                              new IdentityObservationLayerDTO()
                                  .source(
                                      IdentityObservationLayerDTO.SourceEnum.fromValue(
                                          layer.source().name()))
                                  .status(
                                      IdentityObservationLayerDTO.StatusEnum.fromValue(
                                          layer.status().name())))
                      .toList())
              .facts(
                  observation.facts().stream()
                      .map(
                          fact ->
                              new IdentityObservationFactDTO()
                                  .source(
                                      IdentityObservationFactDTO.SourceEnum.fromValue(
                                          fact.source().name()))
                                  .identityId(fact.identityId())
                                  .rowId(fact.rowId())
                                  .username(fact.username())
                                  .email(fact.email())
                                  .tenantId(fact.tenantId())
                                  .adminType(fact.adminType())
                                  .deleted(fact.deleted())
                                  .deletedAt(fact.deletedAt())
                                  .enabled(fact.enabled()))
                      .toList());
      return ResponseEntity.status(observation.status())
          .header("Cache-Control", "no-store")
          .body(dto);
    } catch (AccessDeniedException denied) {
      return ResponseEntity.status(403).header("Cache-Control", "no-store").build();
    } catch (IllegalArgumentException serverFault) {
      // Mapping/service faults are server errors; raw exception text may contain private selectors.
      log.error("Identity occupancy HTTP translation failed: {}", serverFault.getClass().getName());
      return ResponseEntity.status(500).header("Cache-Control", "no-store").build();
    }
  }

  private IdentityProbe parseSelectors(IdentityObservationRequestDTO request) {
    try {
      if (request == null) throw new IllegalArgumentException("Missing exact selectors");
      return IdentityProbe.of(
          request.getIdentityId(), request.getUsername(), request.getEmail(), transcoder);
    } catch (IllegalArgumentException invalid) {
      throw new de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException(
          INVALID_SELECTOR_MESSAGE);
    }
  }

  /** Framework TRACE body logging must not print selectors or nested source facts. */
  private static final class SafeResponse extends IdentityObservationResponseDTO {
    @Override
    public String toString() {
      return "IdentityObservationResponseDTO[redacted]";
    }
  }
}
