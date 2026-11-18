package de.caritas.cob.userservice.api.adapters.web.controller;

import de.caritas.cob.userservice.api.facade.GroupInviteRegistration;
import de.caritas.cob.userservice.api.model.GuestAccountMode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * #1662: public context of a Gesprächskreis invite link. The entry page needs the circle's guest
 * account mode before anybody logs in, to offer "Ohne Konto beitreten", "Konto anlegen" or both,
 * like a direct link does. Only the holder of the link learns it, and nothing else about the
 * circle.
 */
@RestController
@RequiredArgsConstructor
public class GroupInviteContextController {
  private final GroupInviteRegistration registration;

  @GetMapping("/users/chat/{chatId}/invite-context")
  public ResponseEntity<GroupInviteContext> context(
      @PathVariable Long chatId, @RequestParam Long agencyId, @RequestParam String inviteToken) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(
            new GroupInviteContext(
                registration.publicGuestAccountMode(chatId, agencyId, inviteToken)));
  }

  public record GroupInviteContext(GuestAccountMode guestAccountMode) {}
}
