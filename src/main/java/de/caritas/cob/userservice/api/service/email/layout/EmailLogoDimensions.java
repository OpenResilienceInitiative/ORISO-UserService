package de.caritas.cob.userservice.api.service.email.layout;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.Locale;
import java.util.Set;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;

/** Reads optional headers of persisted logo bytes without fetching URLs or decoding pixels. */
record EmailLogoDimensions(int width, int height) {
  // Same allocation bound and data formats as TenantService's BrandingAssetDecoder.
  static final int MAX_DECODED_BYTES = 1024 * 1024;
  private static final Set<String> TYPES =
      Set.of("image/png", "image/jpeg", "image/jpg", "image/x-icon", "image/vnd.microsoft.icon");

  static EmailLogoDimensions read(String stored) {
    if (stored == null || stored.isBlank() || stored.length() * 3L / 4 > MAX_DECODED_BYTES + 128)
      return null;
    String payload = stored.trim();
    if (payload.regionMatches(true, 0, "http://", 0, 7)
        || payload.regionMatches(true, 0, "https://", 0, 8)) return null;
    if (payload.regionMatches(true, 0, "data:", 0, 5)) {
      int separator = payload.indexOf(';');
      if (separator < 0
          || !TYPES.contains(payload.substring(5, separator).toLowerCase(Locale.ROOT))
          || !payload.regionMatches(true, separator, ";base64,", 0, 8)) return null;
      payload = payload.substring(separator + 8);
    }
    if (payload.length() * 3L / 4 > MAX_DECODED_BYTES) return null;
    try {
      byte[] bytes = Base64.getMimeDecoder().decode(payload);
      if (bytes.length == 0 || bytes.length > MAX_DECODED_BYTES) return null;
      try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
        var readers = ImageIO.getImageReaders(input);
        if (!readers.hasNext()) return null;
        var reader = readers.next();
        try {
          if (!Set.of("png", "jpeg", "jpg")
              .contains(reader.getFormatName().toLowerCase(Locale.ROOT))) return null;
          reader.setInput(input, true, true);
          int width = reader.getWidth(0), height = reader.getHeight(0);
          return width > 0 && height > 0 ? new EmailLogoDimensions(width, height) : null;
        } finally {
          reader.dispose();
        }
      }
    } catch (IOException | RuntimeException invalid) {
      // Metadata is optional. Never log input or decoder details, or block delivery.
      return null;
    }
  }
}
