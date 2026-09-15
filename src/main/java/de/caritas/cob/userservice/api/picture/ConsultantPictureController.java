package de.caritas.cob.userservice.api.picture;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping({
  "/useradmin/consultants/{consultantId}/picture",
  "/service/useradmin/consultants/{consultantId}/picture"
})
public class ConsultantPictureController {
  private final ConsultantPictureService service;
  private final ConsultantPictureStore store;
  private final ConsultantPictureAccess access;

  @PutMapping
  public ResponseEntity<Void> put(@PathVariable String consultantId, HttpServletRequest request)
      throws IOException {
    service.put(consultantId, request.getInputStream(), request.getContentType());
    return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
  }

  @GetMapping
  public ResponseEntity<byte[]> get(@PathVariable String consultantId) {
    access.check(consultantId, false);
    var picture = store.read(consultantId);
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(picture.getContentType()))
        .cacheControl(CacheControl.noStore().cachePrivate())
        .header("X-Content-Type-Options", "nosniff")
        .contentLength(picture.getBytes().length)
        .body(picture.getBytes());
  }

  @DeleteMapping
  public ResponseEntity<Void> delete(@PathVariable String consultantId) {
    access.check(consultantId, true);
    store.remove(consultantId);
    return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
  }
}
