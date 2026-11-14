package de.caritas.cob.userservice.api.service.calendar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class CalendarImportUrlValidatorTest {

  /** Names resolve from this table only, so no test touches the network. */
  private static final Map<String, String[]> NAMES =
      Map.of(
          "calendar.example.org", new String[] {"93.184.216.34"},
          "dual.example.org", new String[] {"93.184.216.34", "2606:2800:220:1:248:1893:25c8:1946"},
          "rebind.example.org", new String[] {"93.184.216.34", "10.0.0.7"},
          "intranet.example.org", new String[] {"192.168.1.20"},
          "metadata.example.org", new String[] {"169.254.169.254"},
          "localhost", new String[] {"127.0.0.1"});

  private final CalendarImportUrlValidator validator =
      new CalendarImportUrlValidator(CalendarImportUrlValidatorTest::resolve);

  @ParameterizedTest
  @CsvSource({
    "https://localhost/cal.ics,                      FORBIDDEN_ADDRESS",
    "https://127.0.0.1/cal.ics,                      FORBIDDEN_ADDRESS",
    "https://127.1.2.3:8443/cal.ics,                 FORBIDDEN_ADDRESS",
    "https://10.1.2.3/cal.ics,                       FORBIDDEN_ADDRESS",
    "https://172.16.0.1/cal.ics,                     FORBIDDEN_ADDRESS",
    "https://192.168.0.1/cal.ics,                    FORBIDDEN_ADDRESS",
    "https://169.254.169.254/latest/meta-data,       FORBIDDEN_ADDRESS",
    "https://100.64.0.1/cal.ics,                     FORBIDDEN_ADDRESS",
    "https://100.127.255.254/cal.ics,                FORBIDDEN_ADDRESS",
    "https://0.0.0.0/cal.ics,                        FORBIDDEN_ADDRESS",
    "https://224.0.0.1/cal.ics,                      FORBIDDEN_ADDRESS",
    "https://255.255.255.255/cal.ics,                FORBIDDEN_ADDRESS",
    "https://198.18.0.1/cal.ics,                     FORBIDDEN_ADDRESS",
    "https://198.18.0.0/cal.ics,                     FORBIDDEN_ADDRESS",
    "https://198.19.255.255/cal.ics,                 FORBIDDEN_ADDRESS",
    "https://100.64.0.0/cal.ics,                     FORBIDDEN_ADDRESS",
    "https://100.127.255.255/cal.ics,                FORBIDDEN_ADDRESS",
    "https://192.0.0.0/cal.ics,                      FORBIDDEN_ADDRESS",
    "https://192.0.0.255/cal.ics,                    FORBIDDEN_ADDRESS",
    "https://240.0.0.0/cal.ics,                      FORBIDDEN_ADDRESS",
    "https://[::1]/cal.ics,                          FORBIDDEN_ADDRESS",
    "https://[::]/cal.ics,                           FORBIDDEN_ADDRESS",
    "https://[fc00::1]/cal.ics,                      FORBIDDEN_ADDRESS",
    "https://[fd12:3456:789a::1]/cal.ics,            FORBIDDEN_ADDRESS",
    "https://[fe80::1]/cal.ics,                      FORBIDDEN_ADDRESS",
    "https://[ff02::1]/cal.ics,                      FORBIDDEN_ADDRESS",
    "https://[::ffff:127.0.0.1]/cal.ics,             FORBIDDEN_ADDRESS",
    "https://[::ffff:10.0.0.1]/cal.ics,              FORBIDDEN_ADDRESS",
    "https://[::ffff:169.254.169.254]/cal.ics,       FORBIDDEN_ADDRESS",
    "https://[64:ff9b::a00:1]/cal.ics,               FORBIDDEN_ADDRESS",
    "https://[2002:c0a8:101::1]/cal.ics,             FORBIDDEN_ADDRESS",
    "https://intranet.example.org/cal.ics,           FORBIDDEN_ADDRESS",
    "https://metadata.example.org/cal.ics,           FORBIDDEN_ADDRESS",
    "https://rebind.example.org/cal.ics,             FORBIDDEN_ADDRESS",
    "http://calendar.example.org/cal.ics,            INVALID_URL",
    "http://127.0.0.1/cal.ics,                       INVALID_URL",
    "ftp://calendar.example.org/cal.ics,             INVALID_URL",
    "https://user:secret@calendar.example.org/c.ics, INVALID_URL",
    "https://unknown.example.org/cal.ics,            UNREACHABLE"
  })
  void publicAddressesOf_Should_Refuse(String address, CalendarImportError expected) {
    var uri = validator.parse(address);

    var refusal =
        catchThrowableOfType(CalendarImportException.class, () -> validator.publicAddressesOf(uri));

    assertThat(refusal).as(address).isNotNull();
    assertThat(refusal.error()).as(address).isEqualTo(expected);
    assertThat(refusal.getMessage()).doesNotContain("example", "cal.ics");
  }

  @ParameterizedTest
  @CsvSource({
    "https://calendar.example.org/private/abc.ics?token=1, 93.184.216.34",
    "https://dual.example.org:8443/cal.ics,                93.184.216.34",
    "https://93.184.216.34/cal.ics,                        93.184.216.34",
    "https://[2606:2800:220:1:248:1893:25c8:1946]/cal.ics, 2606:2800:220:1:248:1893:25c8:1946",
    "HTTPS://calendar.example.org/cal.ics,                 93.184.216.34",
    // The public addresses right next to a reserved range.
    "https://100.63.255.255/cal.ics,                       100.63.255.255",
    "https://100.128.0.0/cal.ics,                          100.128.0.0",
    "https://198.17.255.255/cal.ics,                       198.17.255.255",
    "https://198.20.0.0/cal.ics,                           198.20.0.0",
    "https://192.0.1.0/cal.ics,                            192.0.1.0"
  })
  void publicAddressesOf_Should_ReturnTheAddressesToConnectTo(String address, String expected) {
    var addresses = validator.publicAddressesOf(validator.parse(address));

    assertThat(addresses)
        .extracting(InetAddress::getHostAddress)
        .first()
        .as(address)
        .isEqualTo(expected);
  }

  @Test
  void publicAddressesOf_Should_ReturnEveryResolvedAddress() {
    assertThat(validator.publicAddressesOf(URI.create("https://dual.example.org/cal.ics")))
        .extracting(InetAddress::getHostAddress)
        .containsExactly("93.184.216.34", "2606:2800:220:1:248:1893:25c8:1946");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "   ",
        "not a url",
        "calendar.example.org/cal.ics",
        "https://",
        "https:///cal.ics",
        "file:///etc/passwd",
        "https://exa mple.org/cal.ics",
        "mailto:someone@example.org"
      })
  void parse_Should_RefuseWhatIsNoUrl(String text) {
    assertThatThrownBy(() -> validator.parse(text)).isInstanceOf(BadRequestException.class);
  }

  @Test
  void parse_Should_RefuseNull_And_AnOverlongAddress() {
    assertThatThrownBy(() -> validator.parse(null)).isInstanceOf(BadRequestException.class);
    assertThatThrownBy(() -> validator.parse("https://calendar.example.org/" + "a".repeat(2048)))
        .isInstanceOf(BadRequestException.class);
  }

  @Test
  void parse_Should_ReadWebcalAsHttps() {
    assertThat(validator.parse(" webcal://calendar.example.org/cal.ics "))
        .isEqualTo(URI.create("https://calendar.example.org/cal.ics"));
    assertThat(validator.parse("WEBCALS://calendar.example.org/cal.ics").getScheme())
        .isEqualTo("https");
  }

  private static InetAddress[] resolve(String host) throws UnknownHostException {
    var bare = host.startsWith("[") ? host.substring(1, host.length() - 1) : host;
    var known = NAMES.get(bare);
    if (known == null) {
      // Only literals: a name outside the table is unknown instead of being looked up.
      if (!bare.matches("[0-9.]+") && !bare.contains(":")) {
        throw new UnknownHostException(bare);
      }
      return new InetAddress[] {InetAddress.getByName(bare)};
    }
    var addresses = new InetAddress[known.length];
    for (int index = 0; index < known.length; index++) {
      addresses[index] = InetAddress.getByName(known[index]);
    }
    return addresses;
  }
}
