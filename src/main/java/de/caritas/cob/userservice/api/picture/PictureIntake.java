package de.caritas.cob.userservice.api.picture;

import java.io.*;
import java.util.Set;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class PictureIntake {
  public static final int MAX_BYTES = 5 * 1024 * 1024;
  public static final int MAX_DIMENSION = 4096;
  public static final long MAX_PIXELS = 12_000_000;
  private static final byte[] PNG_SIGNATURE = java.util.HexFormat.of().parseHex("89504e470d0a1a0a");

  /** Raw request body, explicitly memory-only: no servlet multipart or ImageIO disk cache. */
  public byte[] read(InputStream body, String contentType) {
    if (!Set.of("image/png", "image/jpeg").contains(contentType == null ? "" : contentType))
      throw PictureException.unsupported();
    try {
      byte[] bytes = body.readNBytes(MAX_BYTES + 1);
      if (bytes.length > MAX_BYTES) throw PictureException.tooLarge();
      // ImageIO may stop at the first image and silently ignore trailing data. Validate its
      // structure, not just a suffix: marker-looking bytes inside payloads are ordinary data.
      if (!(contentType.equals("image/png") ? completePng(bytes) : completeJpeg(bytes)))
        throw PictureException.invalid();
      try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
        var readers = ImageIO.getImageReaders(input);
        if (!readers.hasNext()) throw PictureException.invalid();
        var reader = readers.next();
        try {
          String format = reader.getFormatName();
          if (!(contentType.equals("image/png") && format.equalsIgnoreCase("png"))
              && !(contentType.equals("image/jpeg") && format.equalsIgnoreCase("jpeg")))
            throw PictureException.invalid();
          reader.setInput(input, true, true);
          int width = reader.getWidth(0), height = reader.getHeight(0);
          if (width < 1
              || height < 1
              || width > MAX_DIMENSION
              || height > MAX_DIMENSION
              || (long) width * height > MAX_PIXELS) throw PictureException.invalid();
          // A readable header is insufficient; decode the bounded image and reject recovery
          // warnings.
          boolean[] warning = {false};
          reader.addIIOReadWarningListener((source, message) -> warning[0] = true);
          var image = reader.read(0);
          if (image == null || warning[0]) throw PictureException.invalid();
          image.flush();
          return bytes;
        } finally {
          reader.dispose();
        }
      }
    } catch (IOException | IllegalArgumentException ex) {
      // Exception text and causes can contain private input or decoder diagnostics.
      PictureDiagnostics.withoutRequestContext(
          () ->
              log.debug(
                  "Picture intake rejected: category={}",
                  ex instanceof IOException ? "IO_FAILURE" : "INVALID_ARGUMENT"));
      throw PictureException.invalid();
    }
  }

  private static boolean completePng(byte[] bytes) {
    if (bytes.length < 20 || !java.util.Arrays.equals(bytes, 0, 8, PNG_SIGNATURE, 0, 8))
      return false;
    int position = 8;
    var crc = new java.util.zip.CRC32();
    boolean palette = false, imageData = false, imageDataEnded = false;
    int colorType = -1, bitDepth = 0;
    while (position <= bytes.length - 12) {
      long length = unsignedInt(bytes, position);
      if (length > bytes.length - position - 12) return false;
      int end = position + 12 + (int) length;
      crc.reset();
      crc.update(bytes, position + 4, (int) length + 4);
      if (crc.getValue() != unsignedInt(bytes, end - 4)) return false;
      for (int index = position + 4; index < position + 8; index++) {
        int value = bytes[index] & 0xff;
        if (!(value >= 'A' && value <= 'Z') && !(value >= 'a' && value <= 'z')) return false;
      }
      if ((bytes[position + 6] & 0x20) != 0) return false; // Reserved chunk-name bit.
      String type = new String(bytes, position + 4, 4, java.nio.charset.StandardCharsets.US_ASCII);
      if (position == 8 && !type.equals("IHDR")) return false;
      // PNG critical chunks have fixed identity, cardinality and order. ImageIO may ignore
      // unknown critical chunks or duplicate headers instead of reporting invalid input.
      switch (type) {
        case "IHDR" -> {
          if (position != 8 || length != 13) return false;
          bitDepth = bytes[position + 16] & 0xff;
          colorType = bytes[position + 17] & 0xff;
        }
        case "PLTE" -> {
          if (palette
              || imageData
              || length == 0
              || length > 768
              || length % 3 != 0
              || colorType == 0
              || colorType == 4
              || (colorType == 3 && (bitDepth > 8 || length / 3 > (1 << bitDepth)))) return false;
          palette = true;
        }
        case "IDAT" -> {
          if (imageDataEnded || (colorType == 3 && !palette)) return false;
          imageData = true;
        }
        case "IEND" -> {
          return imageData && length == 0 && end == bytes.length;
        }
        default -> {
          if ((bytes[position + 4] & 0x20) == 0) return false;
        }
      }
      if (imageData && !type.equals("IDAT")) imageDataEnded = true;
      position = end;
    }
    return false;
  }

  private static long unsignedInt(byte[] bytes, int position) {
    return ((long) (bytes[position] & 0xff) << 24)
        | ((long) (bytes[position + 1] & 0xff) << 16)
        | ((long) (bytes[position + 2] & 0xff) << 8)
        | (bytes[position + 3] & 0xff);
  }

  private static boolean completeJpeg(byte[] bytes) {
    if (bytes.length < 4 || (bytes[0] & 0xff) != 0xff || (bytes[1] & 0xff) != 0xd8) return false;
    int position = 2;
    boolean entropy = false;
    while (position < bytes.length) {
      // Outside an entropy-coded scan, every segment starts with a marker. Within a scan,
      // FF00 is a stuffed data byte and FFD0..FFD7 are restart markers, not segment lengths.
      if (!entropy && (bytes[position] & 0xff) != 0xff) return false;
      while (position < bytes.length && (bytes[position] & 0xff) != 0xff) position++;
      while (position < bytes.length && (bytes[position] & 0xff) == 0xff) position++;
      if (position == bytes.length) return false;
      int marker = bytes[position++] & 0xff;
      if (marker == 0) {
        if (!entropy) return false;
        continue;
      }
      if (marker == 0xd9) return position == bytes.length;
      if (marker == 0xd8) return false;
      if (marker >= 0xd0 && marker <= 0xd7) {
        if (!entropy) return false;
        continue;
      }
      if (marker == 0x01) continue; // TEM has no length field.
      if (position > bytes.length - 2) return false;
      int length = ((bytes[position] & 0xff) << 8) | (bytes[position + 1] & 0xff);
      if (length < 2 || length > bytes.length - position) return false;
      position += length;
      // SOS starts a scan; DNL may occur within one. Other segments end the current scan.
      entropy = marker == 0xda || (entropy && marker == 0xdc);
    }
    return false;
  }
}
