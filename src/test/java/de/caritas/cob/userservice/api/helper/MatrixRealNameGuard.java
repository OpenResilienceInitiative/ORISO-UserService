package de.caritas.cob.userservice.api.helper;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.mockito.Mockito;
import org.mockito.invocation.Invocation;

/**
 * ADR-002 §2 regression guard for ORISO-UserService#1200.
 *
 * <p>A counsellor's real name has no legitimate place in <em>any</em> Matrix payload: the profile
 * {@code displayname} is readable by every member of a shared room via {@code /joined_members}, and
 * a system message is a persisted event in the advice seeker's own room. So this asserts on the
 * whole invocation log of the Matrix collaborators rather than on one expected argument — a NEW
 * Matrix call added next to a fixed one cannot reintroduce the leak unnoticed.
 *
 * <p>Matching is deliberately generous, because every near-miss is still a leak:
 *
 * <ul>
 *   <li>arguments are inspected <em>recursively</em> (maps, collections, arrays), so a name nested
 *       in a serialized payload or a JSON body is caught, not just a top-level {@code String};
 *   <li>case and whitespace are normalised and punctuation is treated as a separator, so {@code
 *       "angela musterfrau"}, {@code " Angela Musterfrau "} and {@code "Musterfrau, Angela"} all
 *       match;
 *   <li>the two name parts are matched as tokens in <em>either</em> order and tolerate up to
 *       {@value #MAX_TOKENS_BETWEEN} tokens between them, so a reversed order or an inserted middle
 *       name does not slip through;
 *   <li>the match is a substring of the payload, not an equality check, so {@code "Beraterin Angela
 *       Musterfrau"} and {@code "Angela Musterfrau (Caritas)"} match.
 * </ul>
 *
 * <p>{@link MatrixRealNameGuardTest} pins every one of those variants.
 */
public final class MatrixRealNameGuard {

  /** Tokens tolerated between the two name parts, so an inserted middle name is still caught. */
  private static final int MAX_TOKENS_BETWEEN = 2;

  private MatrixRealNameGuard() {}

  /**
   * @param matrixMock a Mockito mock/spy of a Matrix collaborator — {@link
   *     de.caritas.cob.userservice.api.port.out.MatrixUserClient}, {@code MatrixSynapseService} or
   *     any implementation of them
   * @param firstName the counsellor's real first name
   * @param lastName the counsellor's real last name
   */
  public static void assertNoRealNameReachedMatrix(
      Object matrixMock, String firstName, String lastName) {
    assertNoRealNameReachedMatrix(List.of(matrixMock), firstName, lastName);
  }

  /** Multi-collaborator overload: one assertion across every Matrix mock of a test. */
  public static void assertNoRealNameReachedMatrix(
      Collection<?> matrixMocks, String firstName, String lastName) {
    var first = tokensOf(firstName);
    var last = tokensOf(lastName);
    if (first.isEmpty() || last.isEmpty()) {
      throw new IllegalArgumentException("Both name parts are required to guard against a leak");
    }

    var offendingCalls = new ArrayList<String>();
    for (Object matrixMock : matrixMocks) {
      for (Invocation invocation : Mockito.mockingDetails(matrixMock).getInvocations()) {
        var payload = new StringBuilder();
        flatten(invocation.getArguments(), payload);
        if (carriesName(tokensOf(payload.toString()), first, last)) {
          offendingCalls.add(invocation.toString());
        }
      }
    }

    assertThat(offendingCalls)
        .as(
            "Matrix calls carrying the counsellor's real name '%s %s' (ADR-002 §2, issue #1200)",
            firstName, lastName)
        .isEmpty();
  }

  /**
   * Appends every value reachable from {@code value} to {@code sink}. Maps, collections and arrays
   * are walked so a name inside a serialized message payload is seen; anything else contributes its
   * {@code toString()}, which is what a JSON body or a DTO exposes.
   */
  private static void flatten(Object value, StringBuilder sink) {
    if (value == null) {
      return;
    }
    if (value instanceof Map<?, ?> map) {
      map.forEach(
          (key, mapValue) -> {
            flatten(key, sink);
            flatten(mapValue, sink);
          });
      return;
    }
    if (value instanceof Iterable<?> iterable) {
      iterable.forEach(element -> flatten(element, sink));
      return;
    }
    if (value.getClass().isArray()) {
      if (value instanceof Object[] array) {
        Arrays.stream(array).forEach(element -> flatten(element, sink));
        return;
      }
      sink.append(' ').append(Arrays.deepToString(new Object[] {value}));
      return;
    }
    sink.append(' ').append(value);
  }

  /** Lowercases and splits on everything that is not a letter or digit. */
  private static List<String> tokensOf(String value) {
    if (value == null) {
      return List.of();
    }
    var tokens = new ArrayList<String>();
    var current = new StringBuilder();
    for (var codePoint : value.toLowerCase().toCharArray()) {
      if (Character.isLetterOrDigit(codePoint)) {
        current.append(codePoint);
      } else if (current.length() > 0) {
        tokens.add(current.toString());
        current.setLength(0);
      }
    }
    if (current.length() > 0) {
      tokens.add(current.toString());
    }
    return tokens;
  }

  private static boolean carriesName(
      List<String> payload, List<String> firstName, List<String> lastName) {
    for (int firstAt : startIndicesOf(payload, firstName)) {
      for (int lastAt : startIndicesOf(payload, lastName)) {
        int gap =
            firstAt < lastAt
                ? lastAt - (firstAt + firstName.size())
                : firstAt - (lastAt + lastName.size());
        if (gap >= 0 && gap <= MAX_TOKENS_BETWEEN) {
          return true;
        }
      }
    }
    return false;
  }

  private static List<Integer> startIndicesOf(List<String> payload, List<String> needle) {
    var indices = new ArrayList<Integer>();
    for (int at = 0; at + needle.size() <= payload.size(); at++) {
      if (payload.subList(at, at + needle.size()).equals(needle)) {
        indices.add(at);
      }
    }
    return indices;
  }
}
