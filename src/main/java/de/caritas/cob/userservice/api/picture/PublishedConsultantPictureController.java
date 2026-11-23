package de.caritas.cob.userservice.api.picture;

import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

/**
 * Issue #1049: the advice-seeker facing read of a picture that its owner published. It is a
 * separate route from the internal {@code /useradmin} one so that the advice-seeker path can never
 * inherit an administrative authorization rule, and it answers 404 for every refusal.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping({
  "/users/consultants/{consultantId}/picture",
  "/service/users/consultants/{consultantId}/picture"
})
public class PublishedConsultantPictureController {
  private final ConsultantPictureStore store;

  @GetMapping
  public ResponseEntity<byte[]> get(@PathVariable String consultantId) {
    var picture = store.readPublished(consultantId);
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(picture.getContentType()))
        .cacheControl(CacheControl.noStore().cachePrivate())
        .header("X-Content-Type-Options", "nosniff")
        .contentLength(picture.getBytes().length)
        .body(picture.getBytes());
  }
}
