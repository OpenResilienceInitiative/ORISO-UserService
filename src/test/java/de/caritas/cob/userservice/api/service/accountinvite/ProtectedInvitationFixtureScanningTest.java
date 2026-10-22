package de.caritas.cob.userservice.api.service.accountinvite;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;

class ProtectedInvitationFixtureScanningTest {
  @Test
  void normalComponentScanningDoesNotRegisterTheExplicitlyImportedJpaFixture() {
    var scanner = new ClassPathScanningCandidateComponentProvider(true);

    var candidates =
        scanner.findCandidateComponents("de.caritas.cob.userservice.api.service.accountinvite");

    assertThat(candidates)
        .extracting(BeanDefinition::getBeanClassName)
        .doesNotContain(ProtectedInvitationIssuanceJpaFixture.Components.class.getName());
  }
}
