package de.caritas.cob.userservice.api.adapters.web.controller;

import de.caritas.cob.userservice.api.adapters.web.dto.GuestJoinRequest;
import de.caritas.cob.userservice.api.adapters.web.dto.GuestJoinResponse;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.service.guestjoin.GuestJoinService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

/**
 * Serves the generated {@code joinGuestInvitation} contract operation.
 *
 * <p>The operation has to be answered by the bean that implements {@code UsersApi}: the generated
 * interface maps the path itself and its default implementation replies {@code 501}. A separate
 * {@code @RestController} for the same path is shadowed for JSON requests, because the generated
 * mapping carries {@code consumes}/{@code produces} conditions and is therefore more specific.
 */
@Service
@RequiredArgsConstructor
public class GuestJoinControllerDelegate {
  private final GuestJoinService guestJoinService;

  public ResponseEntity<GuestJoinResponse> joinGuestInvitation(
      String token, GuestJoinRequest request) {
    if (request == null || request.getLanguageFormal() == null) {
      throw new BadRequestException(
          "A guest selection, retry key and language preference are required");
    }
    var joined =
        guestJoinService.join(
            token,
            request.getRetryKey(),
            request.getUsername(),
            request.getAvatarKey(),
            request.getLanguageFormal());
    // Credentials must never be cached by a browser or an intermediary.
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(toResponse(joined));
  }

  private GuestJoinResponse toResponse(GuestJoinService.JoinResponse joined) {
    var response = new GuestJoinResponse();
    response.setUserName(joined.userName());
    response.setAvatarKey(joined.avatarKey());
    response.setSessionId(joined.sessionId());
    response.setTenantId(joined.tenantId());
    response.setConsultingTypeId(joined.consultingTypeId());
    response.setTopicId(joined.topicId());
    response.setAccessToken(joined.accessToken());
    response.setExpiresIn(joined.expiresIn());
    response.setRefreshToken(joined.refreshToken());
    response.setRefreshExpiresIn(joined.refreshExpiresIn());
    return response;
  }
}
