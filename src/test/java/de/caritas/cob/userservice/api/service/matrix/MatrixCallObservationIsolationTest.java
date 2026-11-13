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
  void onlyTheExpectedClassesHoldTheObservationTypes() throws Exception {
    // Scans all production classes: a new holder must be a conscious, reviewed change here.
    // Abstract and nested classes can hold the types too; the default scanner skips them.
    var scanner =
        new org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider(
            false) {
          @Override
          protected boolean isCandidateComponent(
              org.springframework.beans.factory.annotation.AnnotatedBeanDefinition definition) {
            return true;
          }
        };
    scanner.addIncludeFilter((reader, factory) -> true);
    var holders = new java.util.TreeSet<String>();
    for (var candidate : scanner.findCandidateComponents("de.caritas.cob.userservice")) {
      Class<?> type =
          Class.forName(candidate.getBeanClassName(), false, getClass().getClassLoader());
      String name = type.getName(); // binary name: also covers nested and anonymous classes
      // Production classes only: the scan also sees test classes on the classpath.
      // The service's own private nested types (#1628 end/write results) are part of the service.
      if (type == MatrixCallObservationService.class
          || type.getEnclosingClass() == MatrixCallObservationService.class
          || type == MatrixCallObservation.class
          || type == MatrixCallObservationRepository.class
          || name.matches(".*(IT|Test)(\\$.*)?")) continue;
      boolean holds = false;
      // Walk superclasses too: an abstract parent can carry the field for its subclasses.
      for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
        holds |=
            Stream.of(c.getDeclaredFields())
                    .anyMatch(f -> f.getType().getSimpleName().startsWith("MatrixCallObservation"))
                || Stream.of(c.getDeclaredConstructors())
                    .flatMap(k -> Stream.of(k.getParameterTypes()))
                    .anyMatch(t -> t.getSimpleName().startsWith("MatrixCallObservation"));
      }
      if (holds) holders.add(type.getName().substring(type.getName().lastIndexOf('.') + 1));
    }
    // The retention service only deletes expired rows; it neither reads nor grants anything.
    // FE#2209: MatrixSessionCallBindingService reads the row only to REFUSE a binding for a call
    // that was already ended (#1641 "ending wins"); the row never grants anything there.
    assertThat(holders)
        .containsExactly(
            "CallObservationRetentionService",
            "MatrixCallStateController",
            "MatrixCallStateService",
            "MatrixSessionCallBindingService");
  }
}
