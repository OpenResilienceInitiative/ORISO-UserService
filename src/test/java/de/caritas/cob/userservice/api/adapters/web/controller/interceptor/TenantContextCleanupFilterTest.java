package de.caritas.cob.userservice.api.adapters.web.controller.interceptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.caritas.cob.userservice.api.tenant.TenantContext;
import jakarta.servlet.FilterChain;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * The filter is the only thing standing between a tenant set during one request and the next
 * request that happens to get the same pooled thread, so each guarantee is pinned here rather than
 * left to the integration suite, where it only shows up as an ordering-dependent failure somewhere
 * else entirely.
 */
class TenantContextCleanupFilterTest {

  private final TenantContextCleanupFilter filter = new TenantContextCleanupFilter();
  private final MockHttpServletRequest request = new MockHttpServletRequest();
  private final MockHttpServletResponse response = new MockHttpServletResponse();

  @AfterEach
  void clearTenantContext() {
    TenantContext.clear();
  }

  @Test
  void doFilterInternal_Should_ClearTenantContext_When_TheHandlerEstablishedOne() throws Exception {
    FilterChain chain = (req, res) -> TenantContext.setCurrentTenant(42L);

    filter.doFilterInternal(request, response, chain);

    assertThat(TenantContext.getCurrentTenant()).isNull();
  }

  @Test
  void doFilterInternal_Should_ClearTenantContext_When_TheChainThrows() {
    FilterChain chain =
        (req, res) -> {
          TenantContext.setCurrentTenant(42L);
          throw new IllegalStateException("handler blew up");
        };

    // The clear has to sit in a finally: a request that fails must not hand its tenant on.
    assertThatThrownBy(() -> filter.doFilterInternal(request, response, chain))
        .isInstanceOf(IllegalStateException.class);

    assertThat(TenantContext.getCurrentTenant()).isNull();
  }

  @Test
  void doFilterInternal_Should_NotLeakAPreviousTenant_Into_TheHandler() throws Exception {
    TenantContext.setCurrentTenant(99L);
    var seenByHandler = new AtomicReference<Long>(-1L);
    FilterChain chain = (req, res) -> seenByHandler.set(TenantContext.getCurrentTenant());

    filter.doFilterInternal(request, response, chain);

    assertThat(seenByHandler.get()).isNull();
    assertThat(TenantContext.getCurrentTenant()).isNull();
  }

  @Test
  void doFilterInternal_Should_AlwaysInvokeTheChain() throws Exception {
    var invoked = new AtomicBoolean(false);
    FilterChain chain = (req, res) -> invoked.set(true);

    filter.doFilterInternal(request, response, chain);

    assertThat(invoked).isTrue();
  }
}
