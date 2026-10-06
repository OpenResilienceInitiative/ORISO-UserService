package de.caritas.cob.userservice.api.tenant;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import de.caritas.cob.userservice.api.model.ConversationType;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Real public join and real history transport; only synthetic loopback servers are external. */
class GroupCounsellingMatrixRedirectIT extends GroupCounsellingDpaHttpFixture {
  private static final String CREDENTIAL = "synthetic-dedicated-membership-policy-credential";
  private static final AtomicInteger sinkRequests = new AtomicInteger();
  private static final AtomicInteger sinkCredentials = new AtomicInteger();
  private static final AtomicInteger sourceRequests = new AtomicInteger();
  private static final AtomicInteger sourceCredentials = new AtomicInteger();
  private static HttpServer source;
  private static HttpServer sink;
  private static String location;

  @DynamicPropertySource
  static void realHistoryEndpoint(DynamicPropertyRegistry registry) throws Exception {
    source = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    sink = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    var redirectSink =
        (com.sun.net.httpserver.HttpHandler)
            exchange -> {
              sinkRequests.incrementAndGet();
              if (CREDENTIAL.equals(
                  exchange.getRequestHeaders().getFirst("X-Oriso-Group-Policy-Token")))
                sinkCredentials.incrementAndGet();
              var json = new ObjectMapper();
              var answer =
                  (com.fasterxml.jackson.databind.node.ObjectNode)
                      json.readTree(exchange.getRequestBody());
              answer.put("participation", "COMMENCED");
              byte[] bytes = json.writeValueAsBytes(answer);
              exchange.getResponseHeaders().add("Content-Type", "application/json");
              exchange.sendResponseHeaders(200, bytes.length);
              exchange.getResponseBody().write(bytes);
              exchange.close();
            };
    source.createContext("/different-resource", redirectSink);
    sink.createContext("/different-resource", redirectSink);
    source.createContext(
        "/oriso-internal/group-participation-history",
        exchange -> {
          sourceRequests.incrementAndGet();
          if (CREDENTIAL.equals(
              exchange.getRequestHeaders().getFirst("X-Oriso-Group-Policy-Token")))
            sourceCredentials.incrementAndGet();
          exchange.getRequestBody().readAllBytes();
          exchange.getResponseHeaders().add("Location", location);
          byte[] bytes = "synthetic-private-redirect-body".getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(307, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    source.start();
    sink.start();
    registry.add("matrix.group.policy.enabled", () -> true);
    registry.add("matrix.group.policy.token", () -> CREDENTIAL);
    registry.add(
        "matrix.group.participation-history.url",
        () ->
            "http://127.0.0.1:"
                + source.getAddress().getPort()
                + "/oriso-internal/group-participation-history");
  }

  @AfterAll
  static void stopOwnedListeners() {
    if (source != null) source.stop(0);
    if (sink != null) sink.stop(0);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void historyRedirectCannotChangeTheAuthoritativeResourceOrDiscloseItsCredential(
      boolean otherOrigin) throws Exception {
    sourceRequests.set(0);
    sourceCredentials.set(0);
    sinkRequests.set(0);
    sinkCredentials.set(0);
    location =
        (otherOrigin
                ? "http://localhost:" + sink.getAddress().getPort()
                : "http://127.0.0.1:" + source.getAddress().getPort())
            + "/different-resource";
    var chat = storedChat(ConversationType.SELF_HELP);
    chat.setActive(true);
    chats.save(chat);
    var response =
        request(
            "PUT",
            "/users/chat/" + chat.getId() + "/join",
            consultant.getId(),
            consultant.getUsername(),
            "consultant",
            OWNER,
            "");
    assertAll(
        () -> assertEquals(502, response.statusCode(), response.body()),
        () ->
            assertEquals(
                "DPA_POLICY_UNAVAILABLE", response.headers().firstValue("X-Reason").orElse("")),
        () -> assertEquals(1, sourceRequests.get()),
        () -> assertEquals(1, sourceCredentials.get()),
        () -> assertEquals(0, sinkRequests.get()),
        () -> assertEquals(0, sinkCredentials.get()),
        () -> assertEquals(0, ownerReads.get()),
        () -> assertEquals(0, matrixWrites.get()),
        () -> assertEquals(1, participants.findBySeriesId(chat.getId()).size()),
        () -> assertFalse(response.body().contains(CREDENTIAL)),
        () -> assertFalse(response.body().contains("synthetic-private-redirect-body")));
  }

  @Override
  protected ClientHttpResponse additionalExternalResponse(ClientHttpRequest request)
      throws java.io.IOException {
    if (request.getURI().getPath().endsWith("/members"))
      return withSuccess("{\"members\":[]}", MediaType.APPLICATION_JSON).createResponse(request);
    if (request.getURI().getPath().endsWith("/invite")
        || request.getURI().getPath().endsWith("/join")) {
      matrixWrites.incrementAndGet();
      return withSuccess(
              "{\"room_id\":\"!synthetic-stored:synthetic.oriso.test\"}",
              MediaType.APPLICATION_JSON)
          .createResponse(request);
    }
    return super.additionalExternalResponse(request);
  }
}
