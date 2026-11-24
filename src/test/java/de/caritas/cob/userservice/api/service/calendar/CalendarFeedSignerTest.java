package de.caritas.cob.userservice.api.service.calendar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class CalendarFeedSignerTest {

  private static final UUID FEED = UUID.fromString("5b0f3c1e-2a4d-4e7f-9a10-0c8d6f2b7e11");
  private final CalendarFeedSigner signer = new CalendarFeedSigner("synthetic-secret");

  @Test
  void sign_Should_BeStable_UrlSafe_And_22Characters() {
    var signature = signer.sign(FEED);

    assertThat(signature).hasSize(22).matches("[A-Za-z0-9_-]{22}");
    assertThat(signer.sign(FEED)).isEqualTo(signature);
    assertThat(new CalendarFeedSigner("synthetic-secret").sign(FEED)).isEqualTo(signature);
  }

  @Test
  void sign_Should_DependOnFeedAndSecret() {
    assertThat(signer.sign(UUID.randomUUID())).isNotEqualTo(signer.sign(FEED));
    assertThat(new CalendarFeedSigner("another-secret").sign(FEED)).isNotEqualTo(signer.sign(FEED));
  }

  @Test
  void sign_Should_UseAKeyDerivedWithTheFeedLabel_NotTheSecretItself() throws Exception {
    var withSecretItself =
        hmac("synthetic-secret".getBytes(StandardCharsets.UTF_8), FEED.toString());
    var derivedKey =
        hmacBytes("synthetic-secret".getBytes(StandardCharsets.UTF_8), "oriso-calendar-feed-v1");
    var withDerivedKey = hmac(derivedKey, FEED.toString());

    assertThat(signer.sign(FEED)).isEqualTo(withDerivedKey).isNotEqualTo(withSecretItself);
  }

  @Test
  void matches_Should_AcceptOnlyTheExactSignature() {
    var signature = signer.sign(FEED);

    assertThat(signer.matches(FEED, signature)).isTrue();
    assertThat(signer.matches(FEED, signature.substring(0, 21))).isFalse();
    assertThat(signer.matches(FEED, signature + "A")).isFalse();
    assertThat(signer.matches(FEED, flipLast(signature))).isFalse();
    assertThat(signer.matches(FEED, signature.toLowerCase()))
        .isEqualTo(signature.equals(signature.toLowerCase()));
    assertThat(signer.matches(FEED, "")).isFalse();
    assertThat(signer.matches(FEED, null)).isFalse();
    assertThat(signer.matches(null, signature)).isFalse();
    assertThat(signer.matches(UUID.randomUUID(), signature)).isFalse();
  }

  @Test
  void aBlankSecret_Should_StopTheStart() {
    assertThatThrownBy(() -> new CalendarFeedSigner(" ")).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> new CalendarFeedSigner(null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("calendar.feed.secret");
  }

  private static String flipLast(String signature) {
    var last = signature.charAt(signature.length() - 1);
    return signature.substring(0, signature.length() - 1) + (last == 'A' ? 'B' : 'A');
  }

  private static String hmac(byte[] key, String data) throws Exception {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(hmacBytes(key, data))
        .substring(0, 22);
  }

  private static byte[] hmacBytes(byte[] key, String data) throws Exception {
    var mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(key, "HmacSHA256"));
    return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
  }
}
