package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.yaml.snakeyaml.Yaml;

/**
 * Keeps {@code api/userservice-case-handover.yaml} honest.
 *
 * <p>The handover endpoints cannot live in {@code api/userservice.yaml}: that spec is generated
 * with {@code useTags=false}, so every {@code /users} path ends up in {@code UsersApi}, which
 * {@code UserController} implements — the generated default methods would be mapped onto
 * UserController and collide with CaseHandoverController's own mappings at context startup. So the
 * spec is not code-generated, and this test takes over the job the generator would have done: the
 * controller must serve exactly the operations the spec declares, under both the plain and the
 * {@code /service} path, and nothing else.
 */
class CaseHandoverControllerContractTest {

  private static final Path SPEC = Path.of("api/userservice-case-handover.yaml");
  private static final String SERVICE_PREFIX = "/service";

  private static Set<String> specOperations;
  private static Set<String> controllerOperations;
  private static Set<String> controllerServiceOperations;

  @BeforeAll
  @SuppressWarnings("unchecked")
  static void readSpecAndController() throws IOException {
    Map<String, Object> spec;
    try (InputStream in = Files.newInputStream(SPEC)) {
      spec = new Yaml().load(in);
    }
    Map<String, Map<String, Object>> paths = (Map<String, Map<String, Object>>) spec.get("paths");
    specOperations = new TreeSet<>();
    paths.forEach(
        (path, operations) ->
            operations.keySet().forEach(method -> specOperations.add(operation(method, path))));

    controllerOperations = new TreeSet<>();
    controllerServiceOperations = new TreeSet<>();
    for (var method : CaseHandoverController.class.getDeclaredMethods()) {
      RequestMapping mapping =
          AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
      if (mapping == null) {
        continue;
      }
      List<String> httpMethods =
          Arrays.stream(mapping.method()).map(m -> m.name().toLowerCase(Locale.ROOT)).toList();
      assertEquals(
          1,
          httpMethods.size(),
          "each handover handler must map exactly one HTTP method: " + method.getName());
      for (String path : mapping.value()) {
        if (path.startsWith(SERVICE_PREFIX)) {
          controllerServiceOperations.add(
              operation(httpMethods.get(0), path.substring(SERVICE_PREFIX.length())));
        } else {
          controllerOperations.add(operation(httpMethods.get(0), path));
        }
      }
    }
  }

  @Test
  void everySpecOperationIsServedByTheController() {
    Set<String> missing = new LinkedHashSet<>(specOperations);
    missing.removeAll(controllerOperations);
    assertTrue(
        missing.isEmpty(), "declared in the spec but not mapped by the controller: " + missing);
  }

  @Test
  void everyControllerOperationIsDeclaredInTheSpec() {
    Set<String> undocumented = new LinkedHashSet<>(controllerOperations);
    undocumented.removeAll(specOperations);
    assertTrue(
        undocumented.isEmpty(),
        "mapped by the controller but missing from api/userservice-case-handover.yaml: "
            + undocumented);
  }

  /**
   * The frontend calls the {@code /service} variant of every handover endpoint (see endpoints.ts).
   * A handler that forgets the alias is invisible to the app even though every test passes.
   */
  @Test
  void everyOperationIsAlsoServedUnderTheServicePrefix() {
    Set<String> missing = new LinkedHashSet<>(controllerOperations);
    missing.removeAll(controllerServiceOperations);
    assertTrue(missing.isEmpty(), "no /service alias for: " + missing);
  }

  private static String operation(String httpMethod, String path) {
    return httpMethod.toUpperCase(Locale.ROOT) + " " + path;
  }
}
