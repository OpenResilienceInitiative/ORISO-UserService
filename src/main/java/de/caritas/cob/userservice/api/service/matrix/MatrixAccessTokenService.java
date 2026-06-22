package de.caritas.cob.userservice.api.service.matrix;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.service.ConsultantService;
import de.caritas.cob.userservice.api.service.user.UserService;
import java.util.Map;
import java.util.Optional;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class MatrixAccessTokenService {

  private final @NonNull MatrixSynapseService matrixSynapseService;
  private final @NonNull ConsultantService consultantService;
  private final @NonNull UserService userService;
  private final @NonNull MatrixAuthProperties matrixAuthProperties;

  public Optional<MatrixBrowserToken> createBrowserToken(AuthenticatedUser authenticatedUser) {
    if (!isTokenAuthEnabled(authenticatedUser)) {
      log.warn("Matrix token auth disabled for authenticated role");
      return Optional.empty();
    }

    return resolveCurrentMatrixUserId(authenticatedUser)
        .flatMap(matrixUserId -> createBrowserToken(matrixUserId, authenticatedUser));
  }

  public String createServerAccessToken(AuthenticatedUser authenticatedUser) {
    if (!isTokenAuthEnabled(authenticatedUser)) {
      log.warn("Matrix server token auth disabled for authenticated role");
      return null;
    }

    return resolveCurrentMatrixUserId(authenticatedUser)
        .map(matrixUserId -> createServerAccessTokenForMatrixUserId(matrixUserId, "current-user"))
        .orElse(null);
  }

  public String createServerAccessTokenForMatrixUserId(String matrixUserId, String purpose) {
    if (StringUtils.isBlank(matrixUserId)) {
      log.warn("Matrix server token requested without matrixUserId for purpose={}", purpose);
      return null;
    }

    String accessToken = matrixSynapseService.loginAsUserAccessToken(matrixUserId);
    if (StringUtils.isBlank(accessToken)) {
      log.warn(
          "Matrix server token unavailable for matrixUserId={} purpose={}", matrixUserId, purpose);
      return null;
    }

    return accessToken;
  }

  public Optional<String> resolveCurrentMatrixUserId(AuthenticatedUser authenticatedUser) {
    if (authenticatedUser == null || StringUtils.isBlank(authenticatedUser.getUserId())) {
      return Optional.empty();
    }

    if (authenticatedUser.isConsultant()) {
      return consultantService
          .getConsultant(authenticatedUser.getUserId())
          .map(Consultant::getMatrixUserId)
          .filter(StringUtils::isNotBlank);
    }

    if (authenticatedUser.isAdviceSeeker()) {
      return userService
          .getUser(authenticatedUser.getUserId())
          .map(User::getMatrixUserId)
          .filter(StringUtils::isNotBlank);
    }

    return Optional.empty();
  }

  private Optional<MatrixBrowserToken> createBrowserToken(
      String matrixUserId, AuthenticatedUser authenticatedUser) {
    Map<String, Object> tokenResponse =
        matrixSynapseService.loginAsUser(matrixUserId, matrixAuthProperties.getBrowserTokenTtlMs());
    if (tokenResponse == null || tokenResponse.get("access_token") == null) {
      log.warn(
          "Matrix browser token unavailable for role={} matrixUserId={}",
          authenticatedUser.isConsultant() ? "consultant" : "user",
          matrixUserId);
      return Optional.empty();
    }

    return Optional.of(
        MatrixBrowserToken.builder()
            .accessToken(String.valueOf(tokenResponse.get("access_token")))
            .userId(String.valueOf(tokenResponse.getOrDefault("user_id", matrixUserId)))
            .deviceId(String.valueOf(tokenResponse.getOrDefault("device_id", "")))
            .expiresInMs(matrixAuthProperties.getBrowserTokenTtlMs())
            .build());
  }

  private boolean isTokenAuthEnabled(AuthenticatedUser authenticatedUser) {
    if (authenticatedUser == null) {
      return false;
    }
    if (authenticatedUser.isConsultant()) {
      return matrixAuthProperties.isConsultantTokenEnabled();
    }
    if (authenticatedUser.isAdviceSeeker()) {
      return matrixAuthProperties.isUserTokenEnabled();
    }
    return false;
  }
}
