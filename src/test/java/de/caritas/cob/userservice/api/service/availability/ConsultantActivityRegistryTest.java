package de.caritas.cob.userservice.api.service.availability;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ConsultantActivityRegistryTest {

  private ConsultantActivityRegistry registry;

  @BeforeEach
  void setUp() {
    registry = new ConsultantActivityRegistry();
  }

  @Test
  void markAvailable_Should_AddConsultant() {
    registry.markAvailable("consultant-1");

    Set<String> active = registry.filterActive(List.of("consultant-1"), 60_000);
    assertThat(active).containsExactly("consultant-1");
  }

  @Test
  void markAvailable_Should_IgnoreNullId() {
    registry.markAvailable(null);

    Set<String> active = registry.filterActive(List.of(), 60_000);
    assertThat(active).isEmpty();
  }

  @Test
  void markAvailable_Should_IgnoreBlankId() {
    registry.markAvailable("   ");

    Set<String> active = registry.filterActive(List.of("   "), 60_000);
    assertThat(active).isEmpty();
  }

  @Test
  void markUnavailable_Should_RemoveConsultant() {
    registry.markAvailable("consultant-1");
    registry.markUnavailable("consultant-1");

    Set<String> active = registry.filterActive(List.of("consultant-1"), 60_000);
    assertThat(active).isEmpty();
  }

  @Test
  void markUnavailable_Should_IgnoreNullId() {
    registry.markAvailable("consultant-1");
    registry.markUnavailable(null);

    Set<String> active = registry.filterActive(List.of("consultant-1"), 60_000);
    assertThat(active).containsExactly("consultant-1");
  }

  @Test
  void markUnavailable_Should_NotFailWhenConsultantNotPresent() {
    registry.markUnavailable("non-existent");

    Set<String> active = registry.filterActive(List.of("non-existent"), 60_000);
    assertThat(active).isEmpty();
  }

  @Test
  void refreshIfAvailable_Should_KeepConsultantActive() throws InterruptedException {
    registry.markAvailable("consultant-1");
    Thread.sleep(10);
    registry.refreshIfAvailable("consultant-1");

    Set<String> active = registry.filterActive(List.of("consultant-1"), 60_000);
    assertThat(active).containsExactly("consultant-1");
  }

  @Test
  void refreshIfAvailable_Should_NotAddUnavailableConsultant() {
    registry.refreshIfAvailable("consultant-1");

    Set<String> active = registry.filterActive(List.of("consultant-1"), 60_000);
    assertThat(active).isEmpty();
  }

  @Test
  void refreshIfAvailable_Should_IgnoreNullId() {
    registry.markAvailable("consultant-1");
    registry.refreshIfAvailable(null);

    Set<String> active = registry.filterActive(List.of("consultant-1"), 60_000);
    assertThat(active).containsExactly("consultant-1");
  }

  @Test
  void filterActive_Should_ReturnOnlyActiveWithinWindow() throws InterruptedException {
    registry.markAvailable("consultant-1");
    Thread.sleep(100);
    registry.markAvailable("consultant-2");

    // window of 50ms — only consultant-2 is within window
    Set<String> active = registry.filterActive(List.of("consultant-1", "consultant-2"), 50);
    assertThat(active).containsExactly("consultant-2");
  }

  @Test
  void filterActive_Should_ReturnEmptyWhenNoConsultantsGiven() {
    registry.markAvailable("consultant-1");

    Set<String> active = registry.filterActive(List.of(), 60_000);
    assertThat(active).isEmpty();
  }

  @Test
  void filterActive_Should_OnlyReturnSubsetMatchingGivenIds() {
    registry.markAvailable("consultant-1");
    registry.markAvailable("consultant-2");

    Set<String> active = registry.filterActive(List.of("consultant-1"), 60_000);
    assertThat(active).containsExactly("consultant-1");
  }

  @Test
  void multipleConsultants_Should_TrackIndependently() {
    registry.markAvailable("c1");
    registry.markAvailable("c2");
    registry.markUnavailable("c1");

    Set<String> active = registry.filterActive(List.of("c1", "c2"), 60_000);
    assertThat(active).containsExactly("c2");
  }
}
