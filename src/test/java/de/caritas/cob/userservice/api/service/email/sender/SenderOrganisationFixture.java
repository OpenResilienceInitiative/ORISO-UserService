package de.caritas.cob.userservice.api.service.email.sender;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import java.util.Map;
import java.util.Optional;

/** A real {@link SenderOrganisationResolver} over canned Admin data instead of TenantService. */
public final class SenderOrganisationFixture {

  /** The platform owner as a test enters it — deliberately not a "Musterstraße" sample. */
  public static final SenderOrganisation PLATFORM_OWNER =
      new SenderOrganisation("ORISO", "Betreiberweg 1, 10115 Berlin", "info@betreiber.example");

  private SenderOrganisationFixture() {}

  public static SenderOrganisationResolver platformOwner() {
    return resolving(PLATFORM_OWNER, Map.of());
  }

  public static SenderOrganisationResolver nobody() {
    return resolving(null, Map.of());
  }

  /**
   * @param platform the platform owner's data, or {@code null} for "nothing entered"
   * @param traeger each Träger's own data by tenant id; absent ids have none
   */
  public static SenderOrganisationResolver resolving(
      SenderOrganisation platform, Map<Long, SenderOrganisation> traeger) {
    PlatformOperatorOrganisationClient platformClient =
        mock(PlatformOperatorOrganisationClient.class);
    lenient().when(platformClient.fetch()).thenReturn(Optional.ofNullable(platform));
    TraegerOrganisationClient traegerClient = mock(TraegerOrganisationClient.class);
    lenient()
        .when(traegerClient.fetch(any()))
        .thenAnswer(
            invocation -> Optional.ofNullable(traeger.get(invocation.<Long>getArgument(0))));
    return new SenderOrganisationResolver(platformClient, traegerClient);
  }
}
