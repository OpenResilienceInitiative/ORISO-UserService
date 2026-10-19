package de.caritas.cob.userservice.api.service.email.sender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Frank, 2026-09-23: the footer's sender block comes from what the platform owner enters in the
 * Admin panel; a Träger overrides it field by field with its own data.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SenderOrganisationResolverTest {

  private static final long TRAEGER_ID = 84L;

  private static final SenderOrganisation OPERATOR =
      new SenderOrganisation(
          "Betreiber gGmbH", "Betreiberweg 1, 10115 Berlin", "info@betreiber.example");

  @Mock private PlatformOperatorOrganisationClient platformOperator;
  @Mock private TraegerOrganisationClient traeger;

  private SenderOrganisationResolver resolver;

  @BeforeEach
  void setUp() {
    resolver = new SenderOrganisationResolver(platformOperator, traeger);
    when(platformOperator.fetch()).thenReturn(Optional.of(OPERATOR));
    when(traeger.fetch(any())).thenReturn(Optional.empty());
  }

  @Test
  void platform_isThePlatformOwnersMasterData() {
    assertThat(resolver.platform()).isEqualTo(OPERATOR);
  }

  @Test
  void forTenant_keepsTheTraegerName_andInheritsAddressAndContact_When_theTraegerHasNoAddress() {
    when(traeger.fetch(TRAEGER_ID))
        .thenReturn(Optional.of(new SenderOrganisation("Träger Nord e.V.", null, null)));

    SenderOrganisation sender = resolver.forTenant(TRAEGER_ID);

    assertThat(sender.name()).isEqualTo("Träger Nord e.V.");
    assertThat(sender.address()).isEqualTo("Betreiberweg 1, 10115 Berlin");
    assertThat(sender.contactLine()).isEqualTo("info@betreiber.example");
  }

  @Test
  void forTenant_usesTheTraegersOwnAddress_When_itHasOne() {
    when(traeger.fetch(TRAEGER_ID))
        .thenReturn(
            Optional.of(
                new SenderOrganisation("Träger Nord e.V.", "Nordstraße 5, 24103 Kiel", "")));

    SenderOrganisation sender = resolver.forTenant(TRAEGER_ID);

    assertThat(sender.address()).isEqualTo("Nordstraße 5, 24103 Kiel");
    assertThat(sender.contactLine())
        .as("a blank Träger value is no value — the platform owner's still applies")
        .isEqualTo("info@betreiber.example");
  }

  /**
   * The contact line is one field: a Träger that entered only its phone gets a line with only its
   * phone, never its phone next to the platform owner's e-mail — that would name two organisations
   * in one line.
   */
  @Test
  void forTenant_replacesThePlatformOwnersWholeContactLine_When_theTraegerEnteredAnyContact() {
    when(traeger.fetch(TRAEGER_ID))
        .thenReturn(
            Optional.of(
                new SenderOrganisation(
                    "Caritasverband Nord e.V.",
                    null,
                    SenderOrganisation.contactLine(null, "+49 431 123-0"))));

    SenderOrganisation sender = resolver.forTenant(TRAEGER_ID);

    assertThat(sender.name()).isEqualTo("Caritasverband Nord e.V.");
    assertThat(sender.address()).isEqualTo("Betreiberweg 1, 10115 Berlin");
    assertThat(sender.contactLine()).isEqualTo("+49 431 123-0");
  }

  @Test
  void forTenant_isThePlatformOwner_When_theTenantDoesNotExistYet() {
    assertThat(resolver.forTenant(TRAEGER_ID)).isEqualTo(OPERATOR);
  }

  @Test
  void forTenant_neverAsksForATraeger_When_thereIsNoTenant() {
    assertThat(resolver.forTenant(null)).isEqualTo(OPERATOR);
    verify(traeger, never()).fetch(any());
  }

  @Test
  void nobodyEnteredAnything_leavesEveryFieldEmpty_insteadOfInventingASample() {
    when(platformOperator.fetch()).thenReturn(Optional.empty());

    SenderOrganisation sender = resolver.forTenant(TRAEGER_ID);

    assertThat(sender.name()).isNull();
    assertThat(sender.address()).isNull();
    assertThat(sender.contactLine()).isNull();
  }
}
