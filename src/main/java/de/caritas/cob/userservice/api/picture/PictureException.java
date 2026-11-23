package de.caritas.cob.userservice.api.picture;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/** Public, fixed reason codes only. Never include scanner diagnostics or image metadata. */
@Getter
public final class PictureException extends RuntimeException {
  private final HttpStatus status;

  private PictureException(HttpStatus status, String code) {
    super(code);
    this.status = status;
  }

  public static PictureException tooLarge() {
    return new PictureException(HttpStatus.PAYLOAD_TOO_LARGE, "PICTURE_TOO_LARGE");
  }

  public static PictureException unsupported() {
    return new PictureException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "PICTURE_UNSUPPORTED_TYPE");
  }

  public static PictureException invalid() {
    return new PictureException(HttpStatus.BAD_REQUEST, "PICTURE_INVALID_IMAGE");
  }

  public static PictureException rejected() {
    return new PictureException(HttpStatus.UNPROCESSABLE_ENTITY, "PICTURE_REJECTED");
  }

  public static PictureException unavailable() {
    return new PictureException(HttpStatus.SERVICE_UNAVAILABLE, "PICTURE_SCAN_UNAVAILABLE");
  }
}
