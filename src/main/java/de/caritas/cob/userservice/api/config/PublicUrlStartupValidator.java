package de.caritas.cob.userservice.api.config;

import static org.apache.commons.lang3.StringUtils.isBlank;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Checks every public origin that outgoing mail links to, once, before any bean is created.
 *
 * <p>These origins used to fall back to {@code app.base.url}, which itself defaulted to {@code
 * http://localhost:8082}, so an environment without the variable started fine and mailed links
 * nobody could open (ORISO-Helm#368). Now each one is required, and a missing, relative or template
 * value stops startup with a message that names every offending variable at once.
 *
 * <p>Running as a {@link BeanFactoryPostProcessor} is what makes it "at once": the consuming beans
 * would otherwise fail one by one on an unresolvable placeholder, one restart per variable.
 */
@Component
public class PublicUrlStartupValidator implements BeanFactoryPostProcessor, EnvironmentAware {

  /** Profiles that may point at loopback or example hosts. */
  private static final Set<String> NON_DEPLOYED_PROFILES = Set.of("local", "testing");

  /** Reserved example domains (RFC 2606); a real deployment never mails links to them. */
  private static final List<String> EXAMPLE_DOMAINS =
      List.of("example.com", "example.org", "example.net");

  // Browsers accept legacy decimal, octal and hex IPv4 spellings that URI may either reject or
  // treat as DNS names. Match only a numeric authority, optionally followed by its port.
  private static final Pattern NUMERIC_AUTHORITY =
      Pattern.compile(
          "((?:[0-9]+|0x[0-9a-f]+)(?:\\.(?:[0-9]+|0x[0-9a-f]+))*\\.?)(?::[0-9]+)?",
          Pattern.CASE_INSENSITIVE);

  private record PublicUrl(String property, String envVar, boolean required, String missing) {}

  // Each "missing" message follows the must-be-set convention ConfigEnvExampleContractTest scans
  // for, which ties every required variable to config.env.example and required-environment.md.
  private static final List<PublicUrl> PUBLIC_URLS =
      List.of(
          new PublicUrl(
              "app.base.url", "APP_BASE_URL", true, "app.base.url must be set (APP_BASE_URL)"),
          new PublicUrl(
              "system.notification.frontend.base-url",
              "SYSTEM_NOTIFICATION_FRONTEND_BASE_URL",
              true,
              "system.notification.frontend.base-url must be set"
                  + " (SYSTEM_NOTIFICATION_FRONTEND_BASE_URL)"),
          new PublicUrl(
              "dpa.sign.frontend.base-url",
              "DPA_SIGN_FRONTEND_BASE_URL",
              true,
              "dpa.sign.frontend.base-url must be set (DPA_SIGN_FRONTEND_BASE_URL)"),
          new PublicUrl(
              "magic.link.frontend.base-url",
              "MAGIC_LINK_FRONTEND_BASE_URL",
              true,
              "magic.link.frontend.base-url must be set (MAGIC_LINK_FRONTEND_BASE_URL)"),
          new PublicUrl(
              "account.invite.app.frontend.base-url",
              "ACCOUNT_INVITE_APP_FRONTEND_BASE_URL",
              true,
              "account.invite.app.frontend.base-url must be set"
                  + " (ACCOUNT_INVITE_APP_FRONTEND_BASE_URL)"),
          new PublicUrl(
              "account.invite.admin.frontend.base-url",
              "ACCOUNT_INVITE_ADMIN_FRONTEND_BASE_URL",
              true,
              "account.invite.admin.frontend.base-url must be set"
                  + " (ACCOUNT_INVITE_ADMIN_FRONTEND_BASE_URL)"),
          // Blank keeps self-service password reset switched off (fail closed), as before.
          new PublicUrl(
              "password.reset.frontend.base-url", "PASSWORD_RESET_FRONTEND_BASE_URL", false, null),
          new PublicUrl(
              "password.reset.admin.frontend.base-url",
              "PASSWORD_RESET_ADMIN_FRONTEND_BASE_URL",
              false,
              null));

  private Environment environment;

  @Override
  public void setEnvironment(Environment environment) {
    this.environment = environment;
  }

  @Override
  public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
    validate(environment);
  }

  /** Throws one {@link IllegalStateException} listing every public origin that is unusable. */
  public static void validate(Environment environment) {
    boolean deployed = !environment.matchesProfiles(NON_DEPLOYED_PROFILES.toArray(String[]::new));
    List<String> problems = new ArrayList<>();
    for (PublicUrl url : PUBLIC_URLS) {
      String value = read(environment, url.property());
      if (isBlank(value)) {
        if (url.required()) {
          problems.add(url.missing());
        }
        continue;
      }
      String shapeProblem = shapeProblem(value.trim(), deployed);
      if (shapeProblem != null) {
        problems.add(
            url.property() + " " + shapeProblem + ", got '" + value + "' (" + url.envVar() + ")");
      }
    }
    if (!problems.isEmpty()) {
      throw new IllegalStateException(
          "Public URLs used in outgoing mail are not configured for this environment:\n  - "
              + String.join("\n  - ", problems));
    }
  }

  /** An unresolvable {@code ${VAR}} counts as missing, not as a crash with a generic message. */
  private static String read(Environment environment, String property) {
    try {
      return environment.getProperty(property);
    } catch (IllegalArgumentException unresolvable) {
      return null;
    }
  }

  private static String shapeProblem(String value, boolean deployed) {
    URI uri;
    try {
      uri = URI.create(value);
    } catch (IllegalArgumentException malformed) {
      return "is not a valid URL";
    }
    String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
    if (!("http".equals(scheme) || "https".equals(scheme))) {
      return "must be an absolute http(s) URL with a host";
    }
    // Credentials in a mailed link would leak to every recipient.
    if (uri.getRawUserInfo() != null) {
      return "must carry no user info";
    }
    if (deployed && isNoncanonicalNumericHost(uri)) {
      return "uses a noncanonical numeric IP alias,"
          + " which cannot be this environment's public host";
    }
    if (isBlank(uri.getHost())) {
      return "must be an absolute http(s) URL with a host";
    }
    // Routes are appended to these origins; a query or fragment would swallow them. A path
    // prefix stays allowed: the admin password-reset origin is <host>/admin.
    if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
      return "must carry no query or fragment";
    }
    String host = uri.getHost().toLowerCase(Locale.ROOT);
    // A final root dot is valid for a fully qualified DNS name. Normalize only classification;
    // retain the explicitly configured URL and still reject localhost/example hosts with a dot.
    if (host.endsWith(".")) {
      host = host.substring(0, host.length() - 1);
    }
    if (deployed && isPlaceholder(host)) {
      return "uses a reserved example or template host (example.com/.org/.net,"
          + " your-domain), loopback or private host (localhost, 127.0.0.1, [::1],"
          + " private/reserved IPv4 or IPv6),"
          + " which cannot be this environment's public host";
    }
    return null;
  }

  private static boolean isNoncanonicalNumericHost(URI uri) {
    String authority = uri.getRawAuthority();
    if (authority == null) {
      return false;
    }
    var numericAuthority = NUMERIC_AUTHORITY.matcher(authority);
    if (!numericAuthority.matches()) {
      return false;
    }
    String host = numericAuthority.group(1);
    if (!host.matches("(?:0|[1-9][0-9]{0,2})(?:\\.(?:0|[1-9][0-9]{0,2})){3}")) {
      return true;
    }
    for (String octet : host.split("\\.")) {
      if (Integer.parseInt(octet) > 255) {
        return true;
      }
    }
    return false;
  }

  private static boolean isPlaceholder(String host) {
    return host.contains("your-domain")
        || host.equals("localhost")
        || host.endsWith(".localhost")
        || isNonPublicIpv4(host)
        || isNonPublicIpv6(host)
        || EXAMPLE_DOMAINS.stream().anyMatch(d -> host.equals(d) || host.endsWith("." + d));
  }

  private static boolean isNonPublicIpv4(String host) {
    if (!host.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) {
      return false;
    }
    String[] octets = host.split("\\.");
    return isNonPublicIpv4(
        Integer.parseInt(octets[0]),
        Integer.parseInt(octets[1]),
        Integer.parseInt(octets[2]),
        Integer.parseInt(octets[3]));
  }

  private static boolean isNonPublicIpv4(int first, int second, int third, int fourth) {
    // These ranges are not public mail-link destinations. Check exact octet boundaries so adjacent
    // public addresses remain valid; in 192.0.0/24 only .9 and .10 are globally reachable per
    // IANA's special-purpose registry. Do not resolve an operator-provided hostname through DNS.
    return first == 0
        || first == 10
        || first == 127
        || (first == 100 && second >= 64 && second <= 127)
        || (first == 169 && second == 254)
        || (first == 172 && second >= 16 && second <= 31)
        || (first == 192
            && (second == 168
                || (second == 0 && (third == 2 || (third == 0 && fourth != 9 && fourth != 10)))
                || (second == 88 && third == 99 && fourth == 2)))
        || (first == 198 && (second == 18 || second == 19 || (second == 51 && third == 100)))
        || (first == 203 && second == 0 && third == 113)
        || first >= 224;
  }

  private static boolean isNonPublicIpv6(String host) {
    if (!host.startsWith("[") || !host.endsWith("]")) {
      return false;
    }
    try {
      var address = InetAddress.getByName(host);
      byte[] bytes = address.getAddress();
      // Java normalizes IPv4-mapped IPv6 literals to four bytes on some platforms. Check either
      // representation against the same IPv4 ranges before considering the native IPv6 flags.
      if (bytes.length == 4) {
        return isNonPublicIpv4(bytes[0] & 0xff, bytes[1] & 0xff, bytes[2] & 0xff, bytes[3] & 0xff);
      }
      boolean mappedIpv4 = bytes.length == 16;
      for (int i = 0; i < 10 && mappedIpv4; i++) {
        mappedIpv4 = bytes[i] == 0;
      }
      mappedIpv4 = mappedIpv4 && bytes[10] == (byte) 0xff && bytes[11] == (byte) 0xff;
      if (mappedIpv4
          && isNonPublicIpv4(
              bytes[12] & 0xff, bytes[13] & 0xff, bytes[14] & 0xff, bytes[15] & 0xff)) {
        return true;
      }
      boolean uniqueLocal = bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc;
      boolean documentation =
          bytes.length == 16
              && bytes[0] == 0x20
              && bytes[1] == 0x01
              && bytes[2] == 0x0d
              && bytes[3] == (byte) 0xb8;
      return address.isLoopbackAddress()
          || address.isAnyLocalAddress()
          || address.isLinkLocalAddress()
          || address.isSiteLocalAddress()
          || address.isMulticastAddress()
          || documentation
          || uniqueLocal;
    } catch (UnknownHostException invalidLiteral) {
      return true;
    }
  }
}
