package de.caritas.cob.userservice.api.adapters.web.controller;

import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.service.agencyinvitelink.CallShareLinkService;
import de.caritas.cob.userservice.api.service.agencyinvitelink.CallShareLinkService.AlreadyInConversationException;
import de.caritas.cob.userservice.api.service.agencyinvitelink.CallShareLinkService.Join;
import de.caritas.cob.userservice.api.service.agencyinvitelink.CallShareLinkService.ShareLink;
import de.caritas.cob.userservice.api.service.agencyinvitelink.InviteLinkStatus;
import de.caritas.cob.userservice.api.service.user.UserAccountService;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Share link of one running call (#1487), next to the call-guest path of #1477. Counsellors in the
 * call create, read and change it; a signed-in guest joins through it.
 */
@RestController
@RequestMapping({"/matrix/calls/share-links", "/service/matrix/calls/share-links"})
@RequiredArgsConstructor
public class CallShareLinkController {

  private final CallShareLinkService service;
  private final AuthenticatedUser authenticatedUser;
  private final UserAccountService userAccountService;

  @Value("${app.base.url:}")
  private String appBaseUrl;

  /** 201 with a new link, 200 with the call's existing link (its settings unchanged). */
  @PostMapping
  public ResponseEntity<CallShareLinkResponse> create(@RequestBody CreateRequest request) {
    requireConsultant();
    var link =
        service.create(
            authenticatedUser.getTenantId(),
            authenticatedUser.getUserId(),
            request.sourceRoomId(),
            request.callId(),
            request.knock(),
            request.temporaryAccount());
    return ResponseEntity.status(link.created() ? HttpStatus.CREATED : HttpStatus.OK)
        .cacheControl(CacheControl.noStore())
        .body(toResponse(link));
  }

  @GetMapping
  public ResponseEntity<CallShareLinkResponse> current(
      @RequestParam String sourceRoomId, @RequestParam String callId) {
    requireConsultant();
    var link =
        service.current(
            authenticatedUser.getTenantId(), authenticatedUser.getUserId(), sourceRoomId, callId);
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(toResponse(link));
  }

  @PatchMapping("/{linkId:[0-9]+}")
  public ResponseEntity<CallShareLinkResponse> update(
      @PathVariable Long linkId, @RequestBody SettingsRequest request) {
    requireConsultant();
    var link =
        service.update(
            authenticatedUser.getTenantId(),
            authenticatedUser.getUserId(),
            linkId,
            request == null ? null : request.knock(),
            request == null ? null : request.temporaryAccount());
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(toResponse(link));
  }

  /** 204: the guest left the call and cannot come back through the link for this call. */
  @PostMapping("/{linkId:[0-9]+}/guests/remove")
  public ResponseEntity<Void> removeGuest(
      @PathVariable Long linkId, @RequestBody RemoveGuestRequest request) {
    requireConsultant();
    service.removeGuest(
        authenticatedUser.getTenantId(),
        authenticatedUser.getUserId(),
        linkId,
        request == null ? null : request.matrixUserId());
    return ResponseEntity.noContent().build();
  }

  /**
   * 200 in both cases: {@code callAdmitted=false} with a knock to poll ({@code GET
   * /users/call-knocks/mine/{knockId}}), then call again; or {@code callAdmitted=true} with the
   * call room. 409 when the guest is a member of the conversation and joins it the normal way.
   */
  @PostMapping("/{token}/join")
  public ResponseEntity<?> join(@PathVariable String token) {
    try {
      var join = service.join(token, userAccountService.retrieveValidatedUser());
      return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(toResponse(join));
    } catch (AlreadyInConversationException member) {
      return ResponseEntity.status(HttpStatus.CONFLICT)
          .cacheControl(CacheControl.noStore())
          .body(Map.of("reason", "already_conversation_member"));
    }
  }

  private void requireConsultant() {
    if (!authenticatedUser.isConsultant()) {
      throw new de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException(
          "Only counsellors share calls");
    }
  }

  private CallShareLinkResponse toResponse(ShareLink view) {
    var link = view.link();
    return new CallShareLinkResponse(
        link.getId(),
        link.getToken(),
        entryUrl(link.getToken()),
        link.getChatType(),
        link.isKnock(),
        view.temporaryAccount(),
        view.temporaryAccountAllowed(),
        InviteLinkStatus.ACTIVE.name().equals(link.getStatus()),
        link.getExpiresAt() == null ? null : link.getExpiresAt().atOffset(ZoneOffset.UTC));
  }

  private static JoinResponse toResponse(Join join) {
    return new JoinResponse(
        join.medium().name(),
        join.admission().name(),
        join.callAdmitted(),
        join.knockId(),
        join.knockStatus() == null ? null : join.knockStatus().name(),
        join.sourceRoomId(),
        join.callId(),
        join.callRoomId(),
        join.guestMatrixUserId());
  }

  /** Same entry route as the other invite links: the frontend's /invite/:token. */
  private String entryUrl(String token) {
    var base = appBaseUrl == null ? "" : appBaseUrl.replaceAll("/+$", "");
    return base + "/invite/" + token;
  }

  public record CreateRequest(
      String sourceRoomId, String callId, Boolean knock, Boolean temporaryAccount) {}

  public record SettingsRequest(Boolean knock, Boolean temporaryAccount) {}

  public record RemoveGuestRequest(String matrixUserId) {}

  /**
   * @param temporaryAccount effective value: the setting, and allowed by platform and Träger
   */
  public record CallShareLinkResponse(
      Long id,
      String token,
      String url,
      String medium,
      boolean knock,
      boolean temporaryAccount,
      boolean temporaryAccountAllowed,
      boolean active,
      OffsetDateTime expiresAt) {}

  public record JoinResponse(
      String medium,
      String admission,
      boolean callAdmitted,
      Long knockId,
      String knockStatus,
      String sourceRoomId,
      String callId,
      String callRoomId,
      String guestMatrixUserId) {}
}
