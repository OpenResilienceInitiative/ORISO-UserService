package de.caritas.cob.userservice.api.adapters.keycloak.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import de.caritas.cob.userservice.api.scheduler.ScheduledLogger;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

class AccountLifecycleRetrySchedulerSpringTest {
  @Test
  void actualScheduledLoggingCanWrapTheCompleteLifecycleRetryOwner() {
    new ApplicationContextRunner()
        .withUserConfiguration(ProductionSchedulerAdvice.class)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              var scheduler = context.getBean(AccountLifecycleRetryScheduler.class);
              assertThat(org.springframework.aop.support.AopUtils.isAopProxy(scheduler)).isTrue();
              scheduler.retry();
              verify(context.getBean(JdbcAccountLifecycleJournal.class)).claimExpired();
              verify(context.getBean(AccountLifecycleDispatcher.class)).retryPending();
              verify(context.getBean(ClaimedPasswordMaintenance.class)).expireSecretCustody();
              verify(context.getBean(SelfPasswordMaintenance.class)).expireSecretCustody();
              var registry = context.getBean(SimpleMeterRegistry.class);
              assertThat(
                      registry
                          .get("userservice.scheduler.executions")
                          .tag("outcome", "success")
                          .counter()
                          .count())
                  .isEqualTo(1);
              assertThat(
                      registry
                          .get("userservice.scheduler.duration")
                          .tag("outcome", "success")
                          .timer()
                          .count())
                  .isEqualTo(1);
            });
  }

  @TestConfiguration(proxyBeanMethods = false)
  @EnableAspectJAutoProxy(proxyTargetClass = true)
  static class ProductionSchedulerAdvice {
    @Bean
    SimpleMeterRegistry meters() {
      return new SimpleMeterRegistry();
    }

    @Bean
    ScheduledLogger scheduledLogger(SimpleMeterRegistry registry) {
      return new ScheduledLogger(registry);
    }

    @Bean
    JdbcAccountLifecycleJournal journal() {
      return mock(JdbcAccountLifecycleJournal.class);
    }

    @Bean
    AccountLifecycleDispatcher dispatcher() {
      return mock(AccountLifecycleDispatcher.class);
    }

    @Bean
    ClaimedPasswordMaintenance claimed() {
      return mock(ClaimedPasswordMaintenance.class);
    }

    @Bean
    SelfPasswordMaintenance self() {
      return mock(SelfPasswordMaintenance.class);
    }

    @Bean
    AccountLifecycleRetryScheduler accountLifecycleRetryScheduler(
        JdbcAccountLifecycleJournal journal,
        AccountLifecycleDispatcher dispatcher,
        ClaimedPasswordMaintenance claimed,
        SelfPasswordMaintenance self) {
      return new AccountLifecycleRetryScheduler(journal, dispatcher, claimed, self);
    }
  }
}
