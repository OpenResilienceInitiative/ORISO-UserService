package de.caritas.cob.userservice.api.testHelper;

import static de.caritas.cob.userservice.api.testHelper.TestConstants.USERNAME;

import java.util.ArrayList;
import java.util.List;
import org.keycloak.representations.idm.UserRepresentation;

public class KeycloakConstants {

  public static final List<UserRepresentation> EMPTY_USER_REPRESENTATION_LIST = new ArrayList<>();
  public static final UserRepresentation USER_REPRESENTATION_WITH_ENCODED_USERNAME =
      new UserRepresentation() {
        {
          setUsername(USERNAME);
        }
      };
}
