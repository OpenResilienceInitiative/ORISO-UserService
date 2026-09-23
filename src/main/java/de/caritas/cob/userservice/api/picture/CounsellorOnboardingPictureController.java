package de.caritas.cob.userservice.api.picture;

import de.caritas.cob.userservice.api.service.accountinvite.onboarding.CounsellorOnboardingService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

/**
 * Issue #1049: the picture step of the public counsellor onboarding wizard. The invitee has no
 * session yet — the raw invite token is the credential, exactly as for the registration and
 * two-factor steps of the same flow. The token is resolved to the consultant it created before any
 * bytes are touched, and the intake, bounded concurrency and fail-closed scan are the very same
 * ones the administrative route uses.
 */
@RestController
@RequiredArgsConstructor
public class CounsellorOnboardingPictureController {
  private final ConsultantPictureService service;
  private final ConsultantPictureStore store;
  private final CounsellorOnboardingService onboarding;

  @PutMapping({
    "/users/account-invites/{token}/onboarding/picture",
    "/service/users/account-invites/{token}/onboarding/picture"
  })
  public ResponseEntity<Void> put(@PathVariable String token, HttpServletRequest request)
      throws IOException {
    onboarding.consultantIdForOnboardingPicture(token);
    service.putForOnboarding(token, request.getInputStream(), request.getContentType());
    return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
  }

  @PutMapping(
      path = {
        "/users/account-invites/{token}/onboarding/picture/visibility",
        "/service/users/account-invites/{token}/onboarding/picture/visibility"
      },
      consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<Void> putVisibility(
      @PathVariable String token, @RequestBody @Valid ConsultantPictureVisibility visibility) {
    onboarding.consultantIdForOnboardingPicture(token);
    store.writeInternalOnlyForOnboarding(token, visibility.internalOnly());
    return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
  }
}
