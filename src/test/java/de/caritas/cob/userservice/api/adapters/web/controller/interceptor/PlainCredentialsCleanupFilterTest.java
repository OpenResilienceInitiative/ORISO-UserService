package de.caritas.cob.userservice.api.adapters.web.controller.interceptor;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import de.caritas.cob.userservice.api.helper.PlainCredentialsHolder;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PlainCredentialsCleanupFilterTest {

  private PlainCredentialsCleanupFilter cleanupFilter;

  @Mock private HttpServletRequest request;

  @Mock private HttpServletResponse response;

  @Mock private FilterChain filterChain;

  @BeforeEach
  void setup() {
    cleanupFilter = new PlainCredentialsCleanupFilter();
  }

  @AfterEach
  void tearDown() {
    PlainCredentialsHolder.clear();
  }

  @Test
  void doFilterInternal_shouldClearPlainCredentialsHolder_afterNormalFlow()
      throws ServletException, IOException {
    PlainCredentialsHolder.set("testuser", "testpassword");

    cleanupFilter.doFilterInternal(request, response, filterChain);

    verify(filterChain, times(1)).doFilter(request, response);
    assertNull(PlainCredentialsHolder.get());
  }

  @Test
  void doFilterInternal_shouldClearPlainCredentialsHolder_whenFilterChainThrows()
      throws ServletException, IOException {
    PlainCredentialsHolder.set("testuser", "testpassword");
    doThrow(new ServletException("deserialization failed"))
        .when(filterChain)
        .doFilter(request, response);

    assertThrows(
        ServletException.class,
        () -> cleanupFilter.doFilterInternal(request, response, filterChain));

    assertNull(PlainCredentialsHolder.get());
  }
}
