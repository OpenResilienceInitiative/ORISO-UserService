package de.caritas.cob.userservice.api.service.email.layout;

import static org.assertj.core.api.Assertions.assertThat;

import de.caritas.cob.userservice.api.service.email.layout.TenantColourGoldenFixture.Case;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Mail colours must equal the web app's design-token colours for every seed of the golden fixture
 * (ADR-026 amendment 2026-10-02, ORISO-UserService#1252). The fixture is generated in
 * ORISO-Frontend from the real {@code applyTenantPalette}; this test never recomputes expectations.
 *
 * <p>Accept/reject and the white-or-dark label decision are asserted exactly. The dark label is
 * compared by hex value too; {@link #MAX_HEX_DISTANCE} is the largest accepted per-channel
 * difference (0 means the port is hex-exact).
 */
class EmailColorsParityTest {

  /** Largest allowed difference of any single RGB channel between mail and web app. */
  static final int MAX_HEX_DISTANCE = 0;

  static java.util.stream.Stream<Case> goldenCases() {
    return TenantColourGoldenFixture.cases().stream();
  }

  @ParameterizedTest
  @MethodSource("goldenCases")
  void usablePrimary_Should_acceptAndRejectLikeTheWebApp(Case golden) {
    String usable = EmailColors.usablePrimary(golden.seed());

    assertThat(usable != null)
        .as("accepted for seed %s", golden.seed())
        .isEqualTo(golden.accepted());
    if (golden.accepted()) {
      assertThat(usable).isEqualTo(golden.primary());
    }
  }

  @ParameterizedTest
  @MethodSource("goldenCases")
  void onPrimary_Should_chooseTheWebAppsButtonLabel(Case golden) {
    if (!golden.accepted()) {
      return;
    }
    String label = EmailColors.onPrimary(golden.primary());

    assertThat(label.equals("#ffffff"))
        .as("white label for %s", golden.seed())
        .isEqualTo(golden.labelIsWhite());
    assertThat(maxChannelDistance(label, golden.onPrimary()))
        .as("label %s vs web app %s for seed %s", label, golden.onPrimary(), golden.seed())
        .isLessThanOrEqualTo(MAX_HEX_DISTANCE);
  }

  /** Guards the HCT port itself, beyond the label: the hover tone is the web app's too. */
  @ParameterizedTest
  @MethodSource("goldenCases")
  void hct_Should_reproduceTheWebAppsHoverTone(Case golden) {
    if (!golden.accepted()) {
      return;
    }
    Hct hct = Hct.fromHex(golden.primary());
    String hover = hct.toneHex((int) Math.round(hct.tone()) - 8);

    assertThat(maxChannelDistance(hover, golden.primaryHover()))
        .as("hover %s vs web app %s for seed %s", hover, golden.primaryHover(), golden.seed())
        .isLessThanOrEqualTo(MAX_HEX_DISTANCE);
  }

  /**
   * CI cannot clone the private ORISO-Frontend repository, so the copy is pinned by checksum (the
   * way the e-mail templates are pinned by {@code manifest.json}). The checksum only proves the
   * copy was not edited by hand; {@code scripts/sync-tenant-colour-golden.sh} refreshes copy and
   * checksum from a Frontend checkout and is how drift is detected.
   */
  @Test
  void fixtureCopy_Should_matchItsPinnedChecksum() throws Exception {
    String pinned =
        new String(read("/email/tenant-colour-golden.json.sha256"), StandardCharsets.UTF_8).trim();
    String actual =
        HexFormat.of()
            .formatHex(
                MessageDigest.getInstance("SHA-256")
                    .digest(read(TenantColourGoldenFixture.RESOURCE)));

    assertThat(actual)
        .as(
            "tenant-colour-golden.json was edited; it is owned by ORISO-Frontend, run"
                + " scripts/sync-tenant-colour-golden.sh")
        .isEqualTo(pinned);
  }

  @Test
  void fixture_Should_coverBothLabelsAndRejections() {
    assertThat(goldenCases().anyMatch(c -> c.accepted() && Boolean.TRUE.equals(c.labelIsWhite())))
        .isTrue();
    assertThat(goldenCases().anyMatch(c -> c.accepted() && Boolean.FALSE.equals(c.labelIsWhite())))
        .isTrue();
    assertThat(goldenCases().anyMatch(c -> !c.accepted())).isTrue();
  }

  static int maxChannelDistance(String first, String second) {
    int a = Integer.parseInt(first.substring(1), 16);
    int b = Integer.parseInt(second.substring(1), 16);
    int max = 0;
    for (int shift = 0; shift <= 16; shift += 8) {
      max = Math.max(max, Math.abs(((a >> shift) & 255) - ((b >> shift) & 255)));
    }
    return max;
  }

  private static byte[] read(String resource) throws IOException {
    try (InputStream in = EmailColorsParityTest.class.getResourceAsStream(resource)) {
      return in.readAllBytes();
    }
  }
}
