package de.caritas.cob.userservice.api.service.calendar;

import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Decides which address a calendar import may read (ADR-032, ORISO-UserService#1645). The
 * counsellor names the address, so without this check the service could be made to call hosts only
 * it can reach: https only, and every address the host resolves to must be a public one. The
 * addresses that passed are handed to the fetcher, which connects to exactly these; resolving again
 * for the connection would let the host answer differently the second time.
 */
@Component
public class CalendarImportUrlValidator {

  static final int MAX_LENGTH = 2048;

  /** How a host name becomes addresses; replaced in tests. */
  @FunctionalInterface
  interface HostResolver {
    InetAddress[] resolve(String host) throws UnknownHostException;
  }

  private final HostResolver resolver;

  @Autowired
  public CalendarImportUrlValidator() {
    this(InetAddress::getAllByName);
  }

  CalendarImportUrlValidator(HostResolver resolver) {
    this.resolver = resolver;
  }

  /**
   * The address as a URI. {@code webcal://}, the scheme calendar apps hand out for subscriptions,
   * reads as https. What can be told from the text alone is checked here, so an address that could
   * never be read is refused before it is stored: https only, and no user name or password in it.
   *
   * @throws BadRequestException when the text is no URL with a host, not https or carries
   *     credentials; the message never quotes the address
   */
  public URI parse(String raw) {
    if (raw == null || raw.isBlank() || raw.trim().length() > MAX_LENGTH) {
      throw new BadRequestException("The calendar address is not a URL.");
    }
    var text = raw.trim();
    var lower = text.toLowerCase(Locale.ROOT);
    if (lower.startsWith("webcal://")) {
      text = "https://" + text.substring("webcal://".length());
    } else if (lower.startsWith("webcals://")) {
      text = "https://" + text.substring("webcals://".length());
    }
    try {
      var uri = new URI(text);
      if (uri.getScheme() == null || uri.getHost() == null || uri.getHost().isBlank()) {
        throw new BadRequestException("The calendar address is not a URL.");
      }
      if (!"https".equalsIgnoreCase(uri.getScheme())) {
        throw new BadRequestException(
            "The calendar address must start with https:// or webcal://.");
      }
      if (uri.getRawUserInfo() != null) {
        throw new BadRequestException(
            "The calendar address must not contain a user name or password.");
      }
      return uri;
    } catch (URISyntaxException invalid) {
      throw new BadRequestException("The calendar address is not a URL.");
    }
  }

  /**
   * The public addresses of the host. The scheme and the credentials are checked again, for an
   * address that reached the database some other way than {@link #parse}.
   *
   * @throws CalendarImportException {@code INVALID_URL} unless plain https without credentials,
   *     {@code UNREACHABLE} when the host has no address, {@code FORBIDDEN_ADDRESS} when one of its
   *     addresses is not public
   */
  public List<InetAddress> publicAddressesOf(URI address) {
    if (!"https".equalsIgnoreCase(address.getScheme())
        || address.getRawUserInfo() != null
        || address.getHost() == null) {
      throw new CalendarImportException(CalendarImportError.INVALID_URL);
    }
    InetAddress[] resolved;
    try {
      resolved = resolver.resolve(address.getHost());
    } catch (UnknownHostException | SecurityException unknown) {
      throw new CalendarImportException(CalendarImportError.UNREACHABLE);
    }
    if (resolved == null || resolved.length == 0) {
      throw new CalendarImportException(CalendarImportError.UNREACHABLE);
    }
    for (var candidate : resolved) {
      if (!isPublic(candidate)) {
        throw new CalendarImportException(CalendarImportError.FORBIDDEN_ADDRESS);
      }
    }
    return List.of(resolved);
  }

  /** Whether an address lies on the public internet, as far as its number tells. */
  static boolean isPublic(InetAddress address) {
    if (address == null
        || address.isAnyLocalAddress()
        || address.isLoopbackAddress()
        || address.isLinkLocalAddress()
        || address.isSiteLocalAddress()
        || address.isMulticastAddress()) {
      return false;
    }
    var bytes = address.getAddress();
    if (address instanceof Inet4Address) {
      return isPublicV4(bytes);
    }
    if (address instanceof Inet6Address) {
      // Unique local fc00::/7.
      if ((bytes[0] & 0xfe) == 0xfc) {
        return false;
      }
      var embedded = embeddedV4(bytes);
      return embedded == null || isPublicV4(embedded);
    }
    return false;
  }

  private static boolean isPublicV4(byte[] bytes) {
    try {
      var v4 = InetAddress.getByAddress(bytes);
      if (v4.isAnyLocalAddress()
          || v4.isLoopbackAddress()
          || v4.isLinkLocalAddress()
          || v4.isSiteLocalAddress()
          || v4.isMulticastAddress()) {
        return false;
      }
    } catch (UnknownHostException impossible) {
      return false;
    }
    int first = bytes[0] & 0xff;
    int second = bytes[1] & 0xff;
    int third = bytes[2] & 0xff;
    return first != 0 // "this network" 0.0.0.0/8
        && !(first == 100 && (second & 0xc0) == 64) // carrier-grade NAT 100.64.0.0/10
        && !(first == 192 && second == 0 && third == 0) // protocol assignments 192.0.0.0/24
        && !(first == 198 && (second & 0xfe) == 18) // benchmarking 198.18.0.0/15
        && first < 240; // reserved 240.0.0.0/4 and broadcast
  }

  /**
   * The IPv4 address an IPv6 address stands for, if it is one of the forms that reach an IPv4 host:
   * IPv4-mapped (::ffff:a.b.c.d), IPv4-compatible (::a.b.c.d), NAT64 (64:ff9b::/96) and 6to4
   * (2002:a.b.c.d::/48).
   */
  private static byte[] embeddedV4(byte[] bytes) {
    boolean leadingZeros = true;
    for (int index = 0; index < 10; index++) {
      leadingZeros &= bytes[index] == 0;
    }
    boolean mapped = leadingZeros && (bytes[10] & 0xff) == 0xff && (bytes[11] & 0xff) == 0xff;
    boolean compatible = leadingZeros && bytes[10] == 0 && bytes[11] == 0;
    boolean nat64 =
        bytes[0] == 0
            && (bytes[1] & 0xff) == 0x64
            && (bytes[2] & 0xff) == 0xff
            && (bytes[3] & 0xff) == 0x9b
            && zero(bytes, 4, 12);
    if (mapped || compatible || nat64) {
      return new byte[] {bytes[12], bytes[13], bytes[14], bytes[15]};
    }
    if ((bytes[0] & 0xff) == 0x20 && bytes[1] == 0x02) {
      return new byte[] {bytes[2], bytes[3], bytes[4], bytes[5]};
    }
    return null;
  }

  private static boolean zero(byte[] bytes, int from, int to) {
    for (int index = from; index < to; index++) {
      if (bytes[index] != 0) {
        return false;
      }
    }
    return true;
  }
}
