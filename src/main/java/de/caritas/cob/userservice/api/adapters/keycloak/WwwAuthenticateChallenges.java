package de.caritas.cob.userservice.api.adapters.keycloak;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;

/** Reads the auth schemes of all challenges in all WWW-Authenticate header fields (RFC 9110). */
final class WwwAuthenticateChallenges {

  private static final Pattern AUTH_PARAM_START = Pattern.compile("^[^\\s=]+\\s*=");

  private WwwAuthenticateChallenges() {}

  static boolean containsScheme(HttpHeaders headers, String scheme) {
    if (headers == null) {
      return false;
    }
    var values = headers.get(HttpHeaders.WWW_AUTHENTICATE);
    if (values == null) {
      return false;
    }
    return values.stream()
        .flatMap(value -> schemesOf(value).stream())
        .anyMatch(scheme::equalsIgnoreCase);
  }

  // A comma-separated element is an auth-param of the preceding challenge when it starts with
  // "name="; otherwise its first token is the scheme of a new challenge.
  static List<String> schemesOf(String headerValue) {
    var schemes = new ArrayList<String>();
    if (headerValue == null) {
      return schemes;
    }
    for (var element : splitOutsideQuotes(headerValue)) {
      var trimmed = element.trim();
      if (trimmed.isEmpty()) {
        continue;
      }
      if (!AUTH_PARAM_START.matcher(trimmed).find()) {
        schemes.add(trimmed.split("\\s+", 2)[0].toLowerCase(Locale.ROOT));
      }
    }
    return schemes;
  }

  private static List<String> splitOutsideQuotes(String value) {
    var elements = new ArrayList<String>();
    var current = new StringBuilder();
    var inQuotes = false;
    for (var i = 0; i < value.length(); i++) {
      var c = value.charAt(i);
      if (inQuotes && c == '\\' && i + 1 < value.length()) {
        current.append(c).append(value.charAt(++i));
      } else if (c == '"') {
        inQuotes = !inQuotes;
        current.append(c);
      } else if (c == ',' && !inQuotes) {
        elements.add(current.toString());
        current.setLength(0);
      } else {
        current.append(c);
      }
    }
    elements.add(current.toString());
    return elements;
  }
}
