package de.caritas.cob.userservice.api.service.journey;

import java.security.SecureRandom;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Ids the server issues (ORISO-UserService#1689). The Admin names what it creates with a draft id
 * ({@code new:<kind>-…}); on saving, each draft id becomes an id of the server, the same one
 * wherever the draft id occurs (a reference stays a reference). Stored ids never change, so answers
 * keyed by question id keep pointing at the same question.
 */
public final class JourneyIds {

  /** The prefix of an id the Admin made up and the server has not issued yet. */
  public static final String DRAFT = "new:";

  private static final Pattern KIND = Pattern.compile("[a-z]{1,16}");
  private static final String ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyz";
  private static final int RANDOM_CHARS = 12;
  private static final SecureRandom RANDOM = new SecureRandom();

  private JourneyIds() {}

  public static boolean isDraft(String id) {
    return id != null && id.startsWith(DRAFT);
  }

  /**
   * A fresh id for a draft one, e.g. {@code step-k3x9…} for {@code new:step-…}; never in {@code
   * taken}.
   */
  public static String issue(String draft, Set<String> taken) {
    var rest = draft.substring(DRAFT.length());
    var dash = rest.indexOf('-');
    var kind = dash > 0 ? rest.substring(0, dash) : rest;
    var prefix = KIND.matcher(kind).matches() ? kind : "id";
    String id;
    do {
      var random = new StringBuilder(prefix).append('-');
      for (int i = 0; i < RANDOM_CHARS; i++) {
        random.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
      }
      id = random.toString();
    } while (taken.contains(id));
    taken.add(id);
    return id;
  }

  /**
   * Replaces the draft ids in a journey or layer body, in place: every {@code "id"} that is a draft
   * gets an issued id, and every reference to it ({@code …Id}, {@code …Ids}) follows it. Returns
   * the mapping, draft to issued.
   */
  public static Map<String, String> issueAll(Object body, Set<String> taken) {
    var issued = new HashMap<String, String>();
    collect(body, issued, taken);
    if (!issued.isEmpty()) {
      replace(body, issued);
    }
    if (refersToDraft(body)) {
      throw new IllegalArgumentException("a reference names a draft id that nothing has");
    }
    return issued;
  }

  /** Whether an id field still names a draft: a reference to something that is not there. */
  private static boolean refersToDraft(Object node) {
    if (node instanceof Map<?, ?> map) {
      for (var entry : map.entrySet()) {
        var key = String.valueOf(entry.getKey());
        var value = entry.getValue();
        if (namesId(key)
            && (value instanceof String text && isDraft(text)
                || value instanceof List<?> ids
                    && ids.stream().anyMatch(id -> id instanceof String text && isDraft(text)))) {
          return true;
        }
        if (refersToDraft(value)) {
          return true;
        }
      }
    } else if (node instanceof List<?> list) {
      return list.stream().anyMatch(JourneyIds::refersToDraft);
    }
    return false;
  }

  private static void collect(Object node, Map<String, String> issued, Set<String> taken) {
    if (node instanceof Map<?, ?> map) {
      if (map.get("id") instanceof String id && isDraft(id) && !issued.containsKey(id)) {
        issued.put(id, issue(id, taken));
      }
      map.values().forEach(value -> collect(value, issued, taken));
    } else if (node instanceof List<?> list) {
      list.forEach(value -> collect(value, issued, taken));
    }
  }

  /** A field that names an id: {@code id}, {@code …Id}, or a list {@code …Ids}. */
  private static boolean namesId(String key) {
    return key.equals("id") || key.endsWith("Id") || key.endsWith("Ids");
  }

  @SuppressWarnings("unchecked")
  private static void replace(Object node, Map<String, String> issued) {
    if (node instanceof Map<?, ?> map) {
      var fields = (Map<String, Object>) map;
      // Only references change, never texts; and only what changes is written.
      fields.entrySet().stream()
          .filter(entry -> namesId(entry.getKey()))
          .filter(entry -> entry.getValue() instanceof String text && issued.containsKey(text))
          .map(Map.Entry::getKey)
          .toList()
          .forEach(key -> fields.put(key, issued.get((String) fields.get(key))));
      fields.forEach(
          (key, value) -> {
            if (namesId(key) && value instanceof List<?> ids) {
              replaceIn((List<Object>) ids, issued);
            } else {
              replace(value, issued);
            }
          });
    } else if (node instanceof List<?> list) {
      list.forEach(value -> replace(value, issued));
    }
  }

  private static void replaceIn(List<Object> ids, Map<String, String> issued) {
    for (int i = 0; i < ids.size(); i++) {
      if (ids.get(i) instanceof String text && issued.containsKey(text)) {
        ids.set(i, issued.get(text));
      }
    }
  }
}
