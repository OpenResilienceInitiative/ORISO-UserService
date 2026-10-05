package de.caritas.cob.userservice.api.testHelper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import de.caritas.cob.userservice.api.config.apiclient.TenantServiceApiControllerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

/**
 * Existing integration fixtures keep the real policy and declare their permitted external owner.
 */
public final class DpaOwnerHttpFixtures implements AutoCloseable {
  private final TenantServiceApiControllerFactory factory;
  private final RestTemplate previousTransport;
  private final MockRestServiceServer server;

  private DpaOwnerHttpFixtures(TenantServiceApiControllerFactory factory, long tenantId) {
    this.factory = factory;
    previousTransport = (RestTemplate) ReflectionTestUtils.getField(factory, "restTemplate");
    // Other external adapters retain their existing transport fixtures.
    var transport = new RestTemplate();
    server = MockRestServiceServer.bindTo(transport).build();
    ReflectionTestUtils.setField(factory, "restTemplate", transport);
    server
        .expect(
            ExpectedCount.between(0, Integer.MAX_VALUE),
            request -> {
              assertEquals(HttpMethod.GET, request.getMethod());
              assertTrue(
                  request.getURI().getPath().endsWith("/tenantadmin/" + tenantId + "/dpa/gate"));
              assertEquals("0", request.getHeaders().getFirst("tenantId"));
            })
        .andRespond(
            withSuccess("{\"dpaPublished\":true,\"dpaSigned\":true}", MediaType.APPLICATION_JSON));
  }

  public static DpaOwnerHttpFixtures permit(
      TenantServiceApiControllerFactory factory, long tenantId) {
    return new DpaOwnerHttpFixtures(factory, tenantId);
  }

  @Override
  public void close() {
    ReflectionTestUtils.setField(factory, "restTemplate", previousTransport);
    server.reset();
  }
}
