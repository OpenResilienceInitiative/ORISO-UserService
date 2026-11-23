package de.caritas.cob.userservice.api.adapters.web.controller;

import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.service.servicenotice.ServiceNoticeConfirmation;
import de.caritas.cob.userservice.api.service.servicenotice.ServiceNoticeConfirmation.Confirmed;
import java.util.NoSuchElementException;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The platform operator's explicit decision to send a planned notice. It records recipients and
 * queues mail; the separate, opt-in sender delivers it. Repeating it is harmless.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping({
  "/users/admin/service-notices/drafts",
  "/service/users/admin/service-notices/drafts"
})
public class ServiceNoticeConfirmController {

  /** The recipient count the operator saw in the dry run; a different count is refused. */
  public record ConfirmRequest(Integer expectedRecipients) {}

  private final @NonNull AuthenticatedUser authenticatedUser;
  private final @NonNull ServiceNoticeConfirmation confirmation;

  @PostMapping("/{campaignKey}/confirm")
  public ResponseEntity<Confirmed> confirm(
      @PathVariable String campaignKey, @RequestBody(required = false) ConfirmRequest request) {
    if (!authenticatedUser.isPlatformAdmin()) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Platform administrator required");
    }
    if (request == null
        || request.expectedRecipients() == null
        || request.expectedRecipients() < 0) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "The recipient count from the dry run is required");
    }
    try {
      return ResponseEntity.ok()
          .cacheControl(CacheControl.noStore())
          .body(
              confirmation.confirm(
                  campaignKey, request.expectedRecipients(), authenticatedUser.getUserId()));
    } catch (NoSuchElementException missing) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Service notice draft not found");
    } catch (ServiceNoticeConfirmation.NotTheDraftOwner foreign) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, foreign.getMessage());
    } catch (ServiceNoticeConfirmation.Refused refused) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, refused.getMessage());
    }
  }
}
