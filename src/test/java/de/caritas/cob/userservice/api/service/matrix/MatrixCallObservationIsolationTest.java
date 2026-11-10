package de.caritas.cob.userservice.api.service.matrix;

import static org.assertj.core.api.Assertions.assertThat;

import de.caritas.cob.userservice.api.model.MatrixCallObservation;
import de.caritas.cob.userservice.api.port.out.MatrixCallObservationRepository;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * An observation (#2166) must never grant access. Every access-granting consumer reads the call
 * binding only; none may even hold the observation repository or service.
 */
class MatrixCallObservationIsolationTest {
  static Stream<Class<?>> accessConsumers() throws Exception {
    return Stream.of(
        MatrixCallGuestService.class,
        de.caritas.cob.userservice.api.service.agencyinvitelink.CallShareLinkService.class,
        de.caritas.cob.userservice.api.service.agencyinvitelink.DirectCallKnockService.class,
        MatrixCallBindingService.class,
        MatrixCallBindingWriter.class,
        MatrixCallLifecycleService.class,
        MatrixCallInviteNotificationService.class,
        de.caritas.cob.userservice.api.service.agencyinvitelink.AgencyInviteLinkService.class);
  }

  @ParameterizedTest
  @MethodSource("accessConsumers")
  void consumerHasNoPathToTheObservationTable(Class<?> consumer) {
    List<Class<?>> forbidden =
        List.of(
            MatrixCallObservationRepository.class,
            MatrixCallObservationService.class,
            MatrixCallObservation.class);
    for (Field field : consumer.getDeclaredFields()) {
      assertThat(forbidden).as(consumer + "." + field.getName()).doesNotContain(field.getType());
    }
    for (Constructor<?> constructor : consumer.getDeclaredConstructors()) {
      assertThat(List.of(constructor.getParameterTypes())).doesNotContainAnyElementsOf(forbidden);
    }
  }

  @Test
  void onlyStateReadingAndRegistrationUseTheObservationTable() throws Exception {
    // The two permitted consumers; adding a third must be a conscious, reviewed change.
    assertThat(
            List.of(MatrixCallStateService.class, MatrixCallObservationService.class).stream()
                .flatMap(c -> Stream.of(c.getDeclaredFields()))
                .map(Field::getType))
        .contains(MatrixCallObservationService.class, MatrixCallObservationRepository.class);
  }
}
