package de.caritas.cob.userservice.api.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * A Matrix display name is readable by every member of a shared room, the advice seeker included.
 * The calls that publish one take a free string, so nothing stops a new provisioning path from
 * concatenating first and last name again. This reads the main sources and refuses any such call
 * whose display-name argument, or the local variable handed in as that argument, is built from a
 * real-name getter.
 */
class MatrixDisplayNameSourceTest {

  private static final Path MAIN_SOURCES = Path.of("src/main/java");

  /** Keycloak's createUser legitimately takes the real name, so the receiver has to be Matrix. */
  private static final Pattern PUBLISHING_CALL =
      Pattern.compile(
          "\\b(?:\\w*[mM]atrix\\w*|sessionRoomGateway)\\s*\\.\\s*"
              + "(createUserIdWithoutReactivation|createUserId|createUser|updateUserDisplayName)\\s*\\(");

  private static final Pattern REAL_NAME_GETTER =
      Pattern.compile("\\bget(FirstName|LastName|FullName)\\s*\\(");

  private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

  /** Rooms that by contract never hold an advice seeker, with the reason they are exempt. */
  private static final Map<String, String> EXEMPT =
      Map.of(
          "SupportRoomService.java",
          "the support room holds exactly the support admin and the counsellor");

  @Test
  void noMatrixDisplayNameIsBuiltFromARealName() throws IOException {
    List<String> offences = new ArrayList<>();
    try (var files = Files.walk(MAIN_SOURCES)) {
      for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
        if (EXEMPT.containsKey(file.getFileName().toString())) {
          continue;
        }
        offences.addAll(offencesIn(file, Files.readString(file)));
      }
    }

    assertThat(offences)
        .as("ask ConsultantDisplayNameResolver for the name instead of reading the real one")
        .isEmpty();
  }

  @Test
  void theScanSeesAnInlineConcatenationAndOneHiddenBehindAVariable() {
    var inline =
        "matrixUserClient.createUserId(name, password, admin.getFirstName() + \" \" + admin.getLastName());";
    var viaVariable =
        "String shown = consultant.getFullName();\nmatrixUserClient.updateUserDisplayName(id, shown);";
    var resolved =
        "String shown = resolver.resolveMatrixDisplayName(consultant);\n"
            + "matrixUserClient.createUserId(consultant.getUsername(), password, shown);";

    var viaAlias =
        "String realName = consultant.getFullName();\nString shown = realName;\n"
            + "matrixUserClient.updateUserDisplayName(id, shown);";
    var cyclicAliases =
        "String first = \"clean\";\nString second = first;\nfirst = second;\n"
            + "matrixUserClient.createUserId(a, b, first);";

    assertThat(offencesIn(Path.of("Inline.java"), inline)).hasSize(1);
    assertThat(offencesIn(Path.of("ViaAlias.java"), viaAlias)).hasSize(1);
    assertThat(offencesIn(Path.of("CyclicAliases.java"), cyclicAliases)).isEmpty();
    assertThat(offencesIn(Path.of("ViaVariable.java"), viaVariable)).hasSize(1);
    assertThat(offencesIn(Path.of("Resolved.java"), resolved)).isEmpty();
    assertThat(
            offencesIn(
                Path.of("Keycloak.java"), inline.replace("matrixUserClient", "identityClient")))
        .isEmpty();
  }

  private static List<String> offencesIn(Path file, String source) {
    List<String> offences = new ArrayList<>();
    var call = PUBLISHING_CALL.matcher(source);
    while (call.find()) {
      var arguments = splitArguments(source, call.end());
      if (arguments.isEmpty()) {
        continue;
      }
      var displayName = arguments.get(arguments.size() - 1).trim();
      if (isBuiltFromARealName(displayName, source)) {
        offences.add(file + ": " + call.group(1) + "(…, " + displayName + ")");
      }
    }
    return offences;
  }

  private static boolean isBuiltFromARealName(String argument, String source) {
    return isBuiltFromARealName(argument, source, new HashSet<>());
  }

  /**
   * Follows local aliases ({@code shown = realName}), so one extra hop does not hide the getter.
   */
  private static boolean isBuiltFromARealName(String expression, String source, Set<String> seen) {
    if (REAL_NAME_GETTER.matcher(expression).find()) {
      return true;
    }
    if (expression.contains("(")) {
      // A call decides the value, not its arguments: resolver.resolve(consultant) is clean even
      // though consultant was loaded somewhere that also reads the real name.
      return false;
    }
    var identifier = IDENTIFIER.matcher(expression);
    while (identifier.find()) {
      var name = identifier.group();
      if (!seen.add(name)) {
        continue;
      }
      var assignment =
          Pattern.compile("\\b" + Pattern.quote(name) + "\\s*=(?!=)([^;]*);").matcher(source);
      while (assignment.find()) {
        if (isBuiltFromARealName(assignment.group(1), source, seen)) {
          return true;
        }
      }
    }
    return false;
  }

  /** The top-level arguments of the call whose opening parenthesis ends at {@code start}. */
  private static List<String> splitArguments(String source, int start) {
    List<String> arguments = new ArrayList<>();
    var current = new StringBuilder();
    int depth = 1;
    boolean inString = false;
    for (int i = start; i < source.length(); i++) {
      char c = source.charAt(i);
      if (c == '"' && source.charAt(i - 1) != '\\') {
        inString = !inString;
      }
      if (!inString) {
        if (c == '(') {
          depth++;
        } else if (c == ')' && --depth == 0) {
          if (!current.toString().isBlank()) {
            arguments.add(current.toString());
          }
          return arguments;
        } else if (c == ',' && depth == 1) {
          arguments.add(current.toString());
          current.setLength(0);
          continue;
        }
      }
      current.append(c);
    }
    return List.of();
  }
}
