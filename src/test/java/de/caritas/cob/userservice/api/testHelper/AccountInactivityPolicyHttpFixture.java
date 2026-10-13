package de.caritas.cob.userservice.api.testHelper;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.sun.net.httpserver.HttpServer;
import de.caritas.cob.userservice.api.config.apiclient.TenantServiceApiControllerFactory;
import de.caritas.cob.userservice.api.workflow.accountinactivity.AccountInactivityService;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

/** Real generated TenantService client against a local policy HTTP boundary. */
public abstract class AccountInactivityPolicyHttpFixture {
  private static final HttpServer SERVER = startServer();

  @Autowired private TenantServiceApiControllerFactory tenantApiFactory;

  @Autowired private AccountInactivityService lifecycle;

  protected void assertDefaultInactivityPolicy(String identityId) {
    var policy = lifecycle.snapshot(identityId).orElseThrow();
    assertEquals(24, policy.assignedMonths());
    assertEquals(0, policy.revision());
    assertEquals(AccountInactivityService.Status.ACTIVE, policy.status());
  }

  @DynamicPropertySource
  static void inactivityPolicyEndpoint(DynamicPropertyRegistry registry) {
    registry.add(
        "tenant.service.api.url", () -> "http://127.0.0.1:" + SERVER.getAddress().getPort());
  }

  @BeforeEach
  void useRealTenantHttpTransport() {
    // Some legacy controller fixtures mock the shared HTTP transport for other services.
    // Keep enrollment's generated client real without changing those unrelated collaborators.
    Object transport = ReflectionTestUtils.getField(tenantApiFactory, "restTemplate");
    if (org.mockito.Mockito.mockingDetails(transport).isMock()) {
      ReflectionTestUtils.setField(tenantApiFactory, "restTemplate", new RestTemplate());
    }
  }

  private static HttpServer startServer() {
    try {
      HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext(
          "/tenant/public/",
          exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (!"GET".equals(exchange.getRequestMethod())
                || !(path.equals("/tenant/public/single")
                    || path.matches("/tenant/public/id/-?\\d+"))) {
              exchange.sendResponseHeaders(404, -1);
              exchange.close();
              return;
            }
            byte[] body =
                """
                {"settings":{"tenantAdminControls":{"accountInactivitySettings":{
                  "askerMonths":24,"consultantMonths":24,"otherMonths":24,"revision":0
                }}}}
                """
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) {
              output.write(body);
            }
          });
      server.start();
      Runtime.getRuntime().addShutdownHook(new Thread(() -> server.stop(0)));
      return server;
    } catch (IOException exception) {
      throw new ExceptionInInitializerError(exception);
    }
  }
}
