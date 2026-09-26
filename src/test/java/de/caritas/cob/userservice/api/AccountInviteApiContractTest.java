package de.caritas.cob.userservice.api;

import static org.assertj.core.api.Assertions.assertThat;

import de.caritas.cob.userservice.api.adapters.web.controller.AccountInviteController;
import de.caritas.cob.userservice.api.adapters.web.controller.AdminSelfAssignmentController;
import de.caritas.cob.userservice.api.exception.httpresponses.customheader.HttpStatusExceptionReason;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.yaml.snakeyaml.Yaml;

/** api/useradminservice.yaml documents the hand-written invite controllers exactly. */
class AccountInviteApiContractTest {

  private final Map<String, Object> specification = load();

  @Test
  void everyInviteAndSelfAssignmentRouteIsDocumented() {
    Map<String, Object> paths = map(specification.get("paths"));
    for (String route : adminRoutes()) {
      String[] methodAndPath = route.split(" ", 2);
      assertThat(paths.containsKey(methodAndPath[1])).as(route).isTrue();
      Map<String, Object> operation = map(map(paths.get(methodAndPath[1])).get(methodAndPath[0]));
      assertThat(operation).as(route).isNotNull();
      // The generator must skip them: UseradminApi would map the same routes a second time.
      assertThat(operation.get("x-internal")).as(route).isEqualTo(true);
    }
  }

  @Test
  void requestAndResponseSchemasListEveryField() {
    Map<String, Object> schemas = map(map(specification.get("components")).get("schemas"));

    assertThat(properties(schemas, "CreateAccountInviteRequest"))
        .isEqualTo(publicFields(AccountInviteController.CreateAccountInviteRequestDTO.class));
    assertThat(properties(schemas, "AccountInvite"))
        .isEqualTo(publicFields(AccountInviteController.AccountInviteResponseDTO.class));
    assertThat(properties(schemas, "SelfAssignmentRequest"))
        .isEqualTo(publicFields(AdminSelfAssignmentController.SelfAssignmentRequestDTO.class));
  }

  @Test
  void theDocumentedConflictReasonsAreTheOnesTheServerSends() {
    Map<String, Object> conflict =
        map(
            map(map(specification.get("components")).get("responses"))
                .get("AccountInviteConflict"));
    List<?> reasons =
        (List<?>) map(map(map(conflict.get("headers")).get("X-Reason")).get("schema")).get("enum");

    Set<String> known =
        Arrays.stream(HttpStatusExceptionReason.values())
            .map(Enum::name)
            .collect(Collectors.toSet());
    assertThat(reasons.stream().map(String::valueOf).toList())
        .containsExactlyInAnyOrder(
            "EMAIL_NOT_AVAILABLE",
            "NO_PENDING_UNIT_ADMIN",
            "UNIT_NOT_CREATED",
            "SELF_ASSIGNMENT_ALREADY_EXISTS",
            "CONSULTANT_IDENTITY_ALREADY_GRANTED")
        .allMatch(known::contains);
  }

  private static Set<String> adminRoutes() {
    Set<String> routes = new HashSet<>();
    for (Class<?> controller :
        List.of(AccountInviteController.class, AdminSelfAssignmentController.class)) {
      for (Method method : controller.getDeclaredMethods()) {
        addRoutes(routes, "get", mappingPaths(method.getAnnotation(GetMapping.class)));
        addRoutes(routes, "post", mappingPaths(method.getAnnotation(PostMapping.class)));
        addRoutes(routes, "put", mappingPaths(method.getAnnotation(PutMapping.class)));
      }
    }
    return routes;
  }

  private static void addRoutes(Set<String> routes, String httpMethod, String[] paths) {
    for (String path : paths) {
      if (path.startsWith("/useradmin/account-invites")
          || path.startsWith("/useradmin/self-assignments")) {
        routes.add(httpMethod + " " + path);
      }
    }
  }

  private static String[] mappingPaths(java.lang.annotation.Annotation mapping) {
    if (mapping instanceof GetMapping get) {
      return get.value();
    }
    if (mapping instanceof PostMapping post) {
      return post.value();
    }
    if (mapping instanceof PutMapping put) {
      return put.value();
    }
    return new String[0];
  }

  private static Set<String> properties(Map<String, Object> schemas, String name) {
    return map(map(schemas.get(name)).get("properties")).keySet();
  }

  private static Set<String> publicFields(Class<?> type) {
    return Arrays.stream(type.getFields())
        .filter(field -> !Modifier.isStatic(field.getModifiers()))
        .map(Field::getName)
        .collect(Collectors.toSet());
  }

  private static Map<String, Object> load() {
    try {
      return new Yaml().load(Files.readString(Path.of("api/useradminservice.yaml")));
    } catch (IOException exception) {
      throw new IllegalStateException(exception);
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(Object value) {
    return (Map<String, Object>) value;
  }
}
