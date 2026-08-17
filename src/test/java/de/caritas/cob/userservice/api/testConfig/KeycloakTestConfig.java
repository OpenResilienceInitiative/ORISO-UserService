package de.caritas.cob.userservice.api.testConfig;

import com.google.common.collect.Maps;
import de.caritas.cob.userservice.api.adapters.keycloak.KeycloakAuthClient;
import de.caritas.cob.userservice.api.adapters.keycloak.KeycloakClient;
import de.caritas.cob.userservice.api.adapters.keycloak.KeycloakMapper;
import de.caritas.cob.userservice.api.adapters.keycloak.KeycloakService;
import de.caritas.cob.userservice.api.admin.service.consultant.validation.UserAccountInputValidator;
import de.caritas.cob.userservice.api.config.auth.UserRole;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.helper.UserHelper;
import de.caritas.cob.userservice.api.port.out.IdentityAccountCreated;
import de.caritas.cob.userservice.api.port.out.IdentityAccountCreation;
import de.caritas.cob.userservice.api.port.out.IdentityClientConfig;
import de.caritas.cob.userservice.api.port.out.IdentityDummyEmailUpdate;
import de.caritas.cob.userservice.api.port.out.IdentityLogin;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.RandomStringUtils;
import org.keycloak.admin.client.resource.UserResource;
import org.keycloak.representations.idm.UserRepresentation;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestTemplate;

@TestConfiguration
@Slf4j
public class KeycloakTestConfig {

  @Bean
  public KeycloakService keycloakService(
      RestTemplate restTemplate,
      AuthenticatedUser authenticatedUser,
      UserAccountInputValidator userAccountInputValidator,
      IdentityClientConfig identityClientConfig,
      KeycloakClient keycloakClient,
      KeycloakMapper keycloakMapper,
      UserHelper userHelper) {

    var keycloakAuthClient =
        new KeycloakAuthClient(restTemplate, authenticatedUser, identityClientConfig);

    return new KeycloakService(
        authenticatedUser,
        userAccountInputValidator,
        identityClientConfig,
        keycloakClient,
        keycloakMapper,
        userHelper,
        keycloakAuthClient) {
      @Override
      public void updateLocale(String userId, String locale) {
        UserResource userResource = keycloakClient.getUsersResource().get(userId);
        UserRepresentation user = getUserRepresentationAndCreateNewUserIfNotExist(userResource);
        super.changeLanguageForTheUser(locale, userResource, user);
      }

      private UserRepresentation getUserRepresentationAndCreateNewUserIfNotExist(
          UserResource userResource) {
        var user = userResource.toRepresentation();
        if (user == null) {
          user = new UserRepresentation();
          user.setAttributes(Maps.newHashMap());
        }
        return user;
      }

      @Override
      public IdentityLogin login(String userName, String password) {
        return new IdentityLogin("", 0, 0, "");
      }

      @Override
      public boolean logout(String refreshToken) {
        return true;
      }

      @Override
      public void updateCurrentUserEmail(String emailAddress) {
        log.debug("KeycloakService.updateCurrentUserEmail called");
      }

      @Override
      public void deleteCurrentUserEmail() {}

      @Override
      public void updateEmailByUsername(String username, String emailAddress) {}

      @Override
      public IdentityAccountCreated createAccount(IdentityAccountCreation account) {
        return new IdentityAccountCreated("keycloak-user-id " + RandomStringUtils.randomNumeric(5));
      }

      @Override
      public boolean isUsernameAvailable(String username) {
        return true;
      }

      @Override
      public String updateDummyEmail(String userId, IdentityDummyEmailUpdate update) {
        var dummyMail = userId + "@dummy.du";
        return dummyMail;
      }

      /**
       * Per-user role state, so role provisioning is observable in integration tests. Previously
       * both mutators discarded their arguments and {@link #findAllByUserId} returned every {@link
       * UserRole}: a service that assigned the wrong roles — or none — still looked correct, and
       * every role-gated assertion passed unconditionally.
       *
       * <p>Seed roles explicitly (via {@code assignRoles}) in a test that needs them.
       */
      private final Map<String, Set<String>> rolesByUserId = new ConcurrentHashMap<>();

      @Override
      public void assignRoles(String userId, Collection<String> roleNames) {
        rolesByUserId
            .computeIfAbsent(userId, key -> ConcurrentHashMap.newKeySet())
            .addAll(roleNames);
      }

      @Override
      public void removeRolesIfPresent(String userId, Collection<String> roleNames) {
        var assigned = rolesByUserId.get(userId);
        if (assigned != null) {
          assigned.removeAll(roleNames);
        }
      }

      @Override
      public void updatePassword(String userId, String password) {}

      @Override
      public void updateProfile(
          String userId, de.caritas.cob.userservice.api.port.out.IdentityProfileUpdate profile) {}

      @Override
      public void rollbackUser(String userId) {}

      @Override
      public void deleteUser(String userId) {}

      @Override
      public void deactivateUser(String userId) {}

      @Override
      public List<String> findAllByUserId(String userId) {
        return List.copyOf(rolesByUserId.getOrDefault(userId, Set.of()));
      }
    };
  }
}
