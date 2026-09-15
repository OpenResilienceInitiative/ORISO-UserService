package de.caritas.cob.userservice.api.picture;

import static org.assertj.core.api.Assertions.*;

import java.awt.image.BufferedImage;
import java.io.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class PictureIntakeTest {
  final PictureIntake intake = new PictureIntake();

  static byte[] png(int width, int height) throws IOException {
    var output = new ByteArrayOutputStream();
    ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", output);
    return output.toByteArray();
  }

  @Test
  void acceptsExactPngBytesWithoutDiskCache() throws Exception {
    byte[] bytes = png(2, 3);
    assertThat(intake.read(new ByteArrayInputStream(bytes), "image/png")).isEqualTo(bytes);
  }

  @Test
  void rejectsUnsupportedAndSpoofedContent() throws Exception {
    assertThatThrownBy(() -> intake.read(new ByteArrayInputStream(png(1, 1)), "image/svg+xml"))
        .isInstanceOf(PictureException.class)
        .hasMessage("PICTURE_UNSUPPORTED_TYPE");
    assertThatThrownBy(() -> intake.read(new ByteArrayInputStream(png(1, 1)), "image/jpeg"))
        .hasMessage("PICTURE_INVALID_IMAGE");
    assertThatThrownBy(
            () -> intake.read(new ByteArrayInputStream("<svg/>".getBytes()), "image/png"))
        .hasMessage("PICTURE_INVALID_IMAGE");
  }

  @Test
  void boundsActualStreamIndependentlyOfDeclaredLength() {
    var stream = new ByteArrayInputStream(new byte[PictureIntake.MAX_BYTES + 1]);
    assertThatThrownBy(() -> intake.read(stream, "image/png")).hasMessage("PICTURE_TOO_LARGE");
    assertThat(stream.available()).isZero();
  }

  @Test
  void rejectsEmptyTruncatedAndExcessiveDimensions() throws Exception {
    byte[] bytes = png(2, 2);
    assertThatThrownBy(() -> intake.read(new ByteArrayInputStream(new byte[0]), "image/png"))
        .hasMessage("PICTURE_INVALID_IMAGE");
    assertThatThrownBy(
            () ->
                intake.read(
                    new ByteArrayInputStream(java.util.Arrays.copyOf(bytes, 40)), "image/png"))
        .hasMessage("PICTURE_INVALID_IMAGE");
    assertThatThrownBy(() -> intake.read(new ByteArrayInputStream(png(4097, 1)), "image/png"))
        .hasMessage("PICTURE_INVALID_IMAGE");
  }

  @Test
  void rejectsPngWithoutTerminalChunk() throws Exception {
    byte[] bytes = png(2, 2);
    assertThatThrownBy(
            () ->
                intake.read(
                    new ByteArrayInputStream(java.util.Arrays.copyOf(bytes, bytes.length - 12)),
                    "image/png"))
        .hasMessage("PICTURE_INVALID_IMAGE");
  }

  @Test
  void acceptsJpegAndRejectsTruncatedJpeg() throws Exception {
    var out = new ByteArrayOutputStream();
    ImageIO.write(new BufferedImage(2, 3, BufferedImage.TYPE_INT_RGB), "jpeg", out);
    byte[] bytes = out.toByteArray();
    assertThat(intake.read(new ByteArrayInputStream(bytes), "image/jpeg")).isEqualTo(bytes);
    assertThatThrownBy(
            () ->
                intake.read(
                    new ByteArrayInputStream(java.util.Arrays.copyOf(bytes, bytes.length - 2)),
                    "image/jpeg"))
        .hasMessage("PICTURE_INVALID_IMAGE");
  }

  @Test
  void rejectsDecodedPixelLimitEvenWhenEachDimensionFits() throws Exception {
    byte[] bytes = png(4000, 3001);
    assertThat(bytes.length).isLessThan(PictureIntake.MAX_BYTES);
    assertThatThrownBy(() -> intake.read(new ByteArrayInputStream(bytes), "image/png"))
        .hasMessage("PICTURE_INVALID_IMAGE");
  }

  @Test
  void acceptsAValidPngAtExactlyFiveMiB() throws Exception {
    byte[] base = png(1, 1);
    byte[] padding = new byte[PictureIntake.MAX_BYTES - base.length - 12];
    byte[] type = "paDD".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    var output = new ByteArrayOutputStream();
    var data = new DataOutputStream(output);
    data.write(base, 0, base.length - 12);
    data.writeInt(padding.length);
    data.write(type);
    data.write(padding);
    var crc = new java.util.zip.CRC32();
    crc.update(type);
    crc.update(padding);
    data.writeInt((int) crc.getValue());
    data.write(base, base.length - 12, 12);
    byte[] bytes = output.toByteArray();
    assertThat(bytes.length).isEqualTo(PictureIntake.MAX_BYTES);
    assertThat(intake.read(new ByteArrayInputStream(bytes), "image/png")).isEqualTo(bytes);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"png", "jpeg"})
  void rejectsDataAfterTheFirstStructuralImageEndEvenWithAnotherEndMarker(String format)
      throws Exception {
    var output = new ByteArrayOutputStream();
    ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), format, output);
    byte[] original = output.toByteArray();
    output.write("NOT-AN-IMAGE-TRAILER".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    int endLength = format.equals("png") ? 12 : 2;
    output.write(original, original.length - endLength, endLength);
    assertThatThrownBy(
            () -> intake.read(new ByteArrayInputStream(output.toByteArray()), "image/" + format))
        .hasMessage("PICTURE_INVALID_IMAGE");
  }

  @Test
  void acceptsJpegEndMarkerInsideLengthDelimitedComment() throws Exception {
    var original = new ByteArrayOutputStream();
    ImageIO.write(new BufferedImage(2, 3, BufferedImage.TYPE_INT_RGB), "jpeg", original);
    byte[] jpeg = original.toByteArray();
    var output = new ByteArrayOutputStream();
    var data = new DataOutputStream(output);
    data.write(jpeg, 0, 2);
    data.writeShort(0xfffe); // COM is length-delimited; its payload is not a marker stream.
    data.writeShort(4);
    data.writeShort(0xffd9);
    data.write(jpeg, 2, jpeg.length - 2);
    byte[] bytes = output.toByteArray();
    assertThat(intake.read(new ByteArrayInputStream(bytes), "image/jpeg")).isEqualTo(bytes);
  }

  @Test
  void acceptsProgressiveJpegWithMultipleEntropyScans() throws Exception {
    var writer = ImageIO.getImageWritersByFormatName("jpeg").next();
    var output = new ByteArrayOutputStream();
    try (var memory = new javax.imageio.stream.MemoryCacheImageOutputStream(output)) {
      writer.setOutput(memory);
      var options = writer.getDefaultWriteParam();
      options.setProgressiveMode(javax.imageio.ImageWriteParam.MODE_DEFAULT);
      var picture = new BufferedImage(24, 24, BufferedImage.TYPE_INT_RGB);
      for (int y = 0; y < 24; y++)
        for (int x = 0; x < 24; x++) picture.setRGB(x, y, (x * 11939) ^ (y * 9123));
      writer.write(null, new javax.imageio.IIOImage(picture, null, null), options);
    } finally {
      writer.dispose();
    }
    byte[] bytes = output.toByteArray();
    assertThat(intake.read(new ByteArrayInputStream(bytes), "image/jpeg")).isEqualTo(bytes);
  }

  @Test
  void pngChunkLengthsProtectEmbeddedMarkersAndRejectCorruptBoundsOrCrc() throws Exception {
    byte[] base = png(2, 2);
    var output = new ByteArrayOutputStream();
    var data = new DataOutputStream(output);
    data.write(base, 0, base.length - 12);
    data.writeInt(12);
    byte[] type = "paDD".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    data.write(type);
    data.write(base, base.length - 12, 12); // Marker bytes inside this chunk are payload.
    var crc = new java.util.zip.CRC32();
    crc.update(type);
    crc.update(base, base.length - 12, 12);
    data.writeInt((int) crc.getValue());
    data.write(base, base.length - 12, 12);
    byte[] bytes = output.toByteArray();
    assertThat(intake.read(new ByteArrayInputStream(bytes), "image/png")).isEqualTo(bytes);
    byte[] oversizedChunk = bytes.clone();
    oversizedChunk[base.length - 12] = (byte) 0xff;
    assertThatThrownBy(() -> intake.read(new ByteArrayInputStream(oversizedChunk), "image/png"))
        .hasMessage("PICTURE_INVALID_IMAGE");
    bytes[bytes.length - 13] ^= 1;
    assertThatThrownBy(() -> intake.read(new ByteArrayInputStream(bytes), "image/png"))
        .hasMessage("PICTURE_INVALID_IMAGE");
  }

  static byte[] chunk(String type, byte[] payload) throws IOException {
    var output = new ByteArrayOutputStream();
    var data = new DataOutputStream(output);
    byte[] name = type.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    data.writeInt(payload.length);
    data.write(name);
    data.write(payload);
    var crc = new java.util.zip.CRC32();
    crc.update(name);
    crc.update(payload);
    data.writeInt((int) crc.getValue());
    return output.toByteArray();
  }

  static byte[] join(byte[]... parts) throws IOException {
    var output = new ByteArrayOutputStream();
    for (byte[] part : parts) output.write(part);
    return output.toByteArray();
  }

  static byte[] invalidCriticalPng(String variant) throws IOException {
    byte[] base = png(2, 2);
    if (variant.equals("duplicate-header")) {
      return join(
          java.util.Arrays.copyOf(base, 33),
          java.util.Arrays.copyOfRange(base, 8, 33),
          java.util.Arrays.copyOfRange(base, 33, base.length));
    }
    return join(
        java.util.Arrays.copyOf(base, base.length - 12),
        chunk("ABCD", new byte[] {1}),
        java.util.Arrays.copyOfRange(base, base.length - 12, base.length));
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"duplicate-header", "unknown-critical"})
  void rejectsInvalidCriticalPngStructure(String variant) throws Exception {
    assertThatThrownBy(
            () -> intake.read(new ByteArrayInputStream(invalidCriticalPng(variant)), "image/png"))
        .hasMessage("PICTURE_INVALID_IMAGE");
  }

  @Test
  void criticalChunksMustBeOrderedUniqueAndImageDataConsecutive() throws Exception {
    byte[] base = png(2, 2);
    byte[] header = java.util.Arrays.copyOf(base, 33);
    byte[] body = java.util.Arrays.copyOfRange(base, 33, base.length);
    byte[] end = java.util.Arrays.copyOfRange(base, base.length - 12, base.length);
    byte[] palette = chunk("PLTE", new byte[] {0, 0, 0});
    byte[] ancillary = chunk("paDD", new byte[] {1});
    for (byte[] invalid :
        java.util.List.of(
            join(
                java.util.Arrays.copyOf(base, 8),
                ancillary,
                java.util.Arrays.copyOfRange(base, 8, base.length)),
            join(header, palette, palette, body),
            join(java.util.Arrays.copyOf(base, base.length - 12), palette, end),
            join(
                java.util.Arrays.copyOf(base, base.length - 12),
                ancillary,
                chunk("IDAT", new byte[0]),
                end),
            join(header, end),
            join(java.util.Arrays.copyOf(base, base.length - 12), chunk("a1CD", new byte[0]), end),
            join(
                java.util.Arrays.copyOf(base, base.length - 12),
                chunk("abcd", new byte[0]),
                end))) {
      assertThatThrownBy(() -> intake.read(new ByteArrayInputStream(invalid), "image/png"))
          .hasMessage("PICTURE_INVALID_IMAGE");
    }
  }

  @Test
  void acceptsConsecutiveDataChunksAndIndexedPalette() throws Exception {
    byte[] base = png(2, 2);
    assertThat(new String(base, 37, 4, java.nio.charset.StandardCharsets.US_ASCII))
        .isEqualTo("IDAT");
    int length = java.nio.ByteBuffer.wrap(base, 33, 4).getInt();
    byte[] payload = java.util.Arrays.copyOfRange(base, 41, 41 + length);
    byte[] split =
        join(
            java.util.Arrays.copyOf(base, 33),
            chunk("IDAT", java.util.Arrays.copyOf(payload, payload.length / 2)),
            chunk(
                "IDAT", java.util.Arrays.copyOfRange(payload, payload.length / 2, payload.length)),
            java.util.Arrays.copyOfRange(base, base.length - 12, base.length));
    assertThat(intake.read(new ByteArrayInputStream(split), "image/png")).isEqualTo(split);
    var output = new ByteArrayOutputStream();
    ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_BYTE_INDEXED), "png", output);
    byte[] indexed = output.toByteArray();
    assertThat(intake.read(new ByteArrayInputStream(indexed), "image/png")).isEqualTo(indexed);
  }
}
