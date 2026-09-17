package de.caritas.cob.userservice.api.service.identity;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.ServiceUnavailableException;
import java.util.List;
import org.junit.jupiter.api.Test;

class GuestIdentitySuggestionServiceTest {
  private final GuestIdentityCatalog catalog = mock(GuestIdentityCatalog.class);
  private final GuestUsernameAvailability availability = mock(GuestUsernameAvailability.class);
  private final GuestIdentitySuggestionService service =
      new GuestIdentitySuggestionService(catalog, availability);

  @Test
  void returnsFourDistinctCheckedNamesAndMatchingAvatars() {
    var bee = new GuestIdentitySuggestion("biene_rayan_1234", "biene_rayan_1234", "bee.svg");
    var cat = new GuestIdentitySuggestion("katze_mika_1234", "katze_mika_1234", "cat.svg");
    var owl = new GuestIdentitySuggestion("eule_mika_1234", "eule_mika_1234", "owl.svg");
    var ant = new GuestIdentitySuggestion("ameise_mika_1234", "ameise_mika_1234", "ant.svg");
    when(catalog.next("de")).thenReturn(bee, bee, cat, owl, ant);
    when(availability.isAvailable(anyString())).thenReturn(true);
    assertThat(service.suggest("de", 4, List.of())).containsExactly(bee, cat, owl, ant);
    verify(availability, times(4)).isAvailable(anyString());
  }

  @Test
  void replacesOneWithoutReturningAnExcludedOrOccupiedName() {
    var excluded = new GuestIdentitySuggestion("biene_rayan_1234", "biene_rayan_1234", "bee.svg");
    var occupied = new GuestIdentitySuggestion("katze_mika_1234", "katze_mika_1234", "cat.svg");
    var free = new GuestIdentitySuggestion("eule_mika_1234", "eule_mika_1234", "owl.svg");
    when(catalog.next("de")).thenReturn(excluded, occupied, free);
    when(availability.isAvailable(occupied.username())).thenReturn(false);
    when(availability.isAvailable(free.username())).thenReturn(true);
    assertThat(service.suggest("de", 1, List.of(excluded.username()))).containsExactly(free);
    verify(availability, never()).isAvailable(excluded.username());
  }

  @Test
  void dependencyFailureDoesNotReturnUncheckedNamesOrKeepRolling() {
    when(catalog.next("de"))
        .thenReturn(new GuestIdentitySuggestion("biene_rayan_1234", "biene_rayan_1234", "bee.svg"));
    when(availability.isAvailable(anyString()))
        .thenThrow(new ServiceUnavailableException("Unavailable"));
    assertThatThrownBy(() -> service.suggest("de", 1, List.of()))
        .isInstanceOf(ServiceUnavailableException.class);
    verify(catalog).next("de");
  }

  @Test
  void collisionsHaveABoundedAttemptLimit() {
    when(catalog.next("de"))
        .thenReturn(new GuestIdentitySuggestion("biene_rayan_1234", "biene_rayan_1234", "bee.svg"));
    assertThatThrownBy(() -> service.suggest("de", 4, List.of()))
        .isInstanceOf(ServiceUnavailableException.class);
    verify(catalog, times(32)).next("de");
  }

  @Test
  void rejectsUnboundedInputsBeforeDependencies() {
    assertThatThrownBy(() -> service.suggest("de", 100, List.of()))
        .isInstanceOf(BadRequestException.class);
    assertThatThrownBy(() -> service.suggest("de", 1, java.util.Collections.nCopies(21, "x")))
        .isInstanceOf(BadRequestException.class);
    verifyNoInteractions(catalog, availability);
  }
}
