package de.caritas.cob.userservice.api.adapters.http;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.client.*;

/** Executes with the configured transport and codecs without rendering private HTTP diagnostics. */
public final class TrackedHttpTransport {
  private final RestTemplate template;

  public TrackedHttpTransport(RestTemplate template) {
    this.template = template;
  }

  public <T> ResponseEntity<T> exchange(
      URI uri, HttpMethod method, HttpEntity<?> entity, Class<T> type) {
    try {
      var request = template.getRequestFactory().createRequest(uri, method);
      template
          .getClientHttpRequestInitializers()
          .forEach(initializer -> initializer.initialize(request));
      var accept = new ArrayList<MediaType>();
      for (var converter : template.getMessageConverters()) {
        if (converter.canRead(type, null)) {
          for (var media : converter.getSupportedMediaTypes(type)) {
            var withoutCharset = new MediaType(media.getType(), media.getSubtype());
            if (!accept.contains(withoutCharset)) accept.add(withoutCharset);
          }
        }
      }
      org.springframework.util.MimeTypeUtils.sortBySpecificity(accept);
      request.getHeaders().setAccept(accept);
      request.getHeaders().putAll(entity.getHeaders());
      Object body = entity.getBody();
      if (body != null) write(body, request);
      else if (request.getHeaders().getContentLength() < 0)
        request.getHeaders().setContentLength(0);
      try (var response = request.execute()) {
        var handler = template.getErrorHandler();
        if (handler.hasError(response)) handler.handleError(uri, method, response);
        // RestTemplate ignores acknowledgement bodies for no-body response contracts.
        T value =
            type == Void.class || type == void.class
                ? null
                : new HttpMessageConverterExtractor<>(type, template.getMessageConverters())
                    .extractData(response);
        return new ResponseEntity<>(value, response.getHeaders(), response.getStatusCode());
      }
    } catch (IOException unavailable) {
      throw new ResourceAccessException("Tracked HTTP transport unavailable");
    }
  }

  /** Narrow generated-client entrypoint; request construction stays in the generated client. */
  public RestTemplate generatedClientView() {
    return new GeneratedClientView();
  }

  /** Concrete-type adapter only: every supported call uses the existing observed transport. */
  private final class GeneratedClientView extends RestTemplate {
    private GeneratedClientView() {
      super(template.getRequestFactory());
      setUriTemplateHandler(template.getUriTemplateHandler());
    }

    @Override
    public <T> ResponseEntity<T> exchange(
        RequestEntity<?> request,
        org.springframework.core.ParameterizedTypeReference<T> responseType) {
      if (!(responseType.getType() instanceof Class<?> type))
        throw new RestClientException("Tracked HTTP response codec unavailable");
      return TrackedHttpTransport.this.exchange(
          requestUri(request), request.getMethod(), request, responseClass(type));
    }

    @Override
    protected <T> T doExecute(
        URI uri,
        String uriTemplate,
        HttpMethod method,
        RequestCallback callback,
        ResponseExtractor<T> extractor) {
      // The generated contract above is the sole supported entrypoint. Other inherited APIs
      // must never open an independent or diagnostically rendered request.
      throw new RestClientException("Unsupported tracked generated-client exchange");
    }
  }

  private URI requestUri(RequestEntity<?> request) {
    if (request instanceof RequestEntity.UriTemplateRequestEntity<?> expanded) {
      if (expanded.getVarsMap() != null)
        return template
            .getUriTemplateHandler()
            .expand(expanded.getUriTemplate(), expanded.getVarsMap());
      return template.getUriTemplateHandler().expand(expanded.getUriTemplate(), expanded.getVars());
    }
    return request.getUrl();
  }

  @SuppressWarnings("unchecked")
  private static <T> Class<T> responseClass(Class<?> type) {
    return (Class<T>) type;
  }

  private void write(Object body, HttpOutputMessage request) throws IOException {
    for (HttpMessageConverter<?> converter : template.getMessageConverters()) {
      if (converter.canWrite(body.getClass(), request.getHeaders().getContentType())) {
        writeUsing(converter, body, request);
        return;
      }
    }
    throw new RestClientException("Tracked HTTP request codec unavailable");
  }

  @SuppressWarnings("unchecked")
  private static void writeUsing(
      HttpMessageConverter<?> converter, Object body, HttpOutputMessage request)
      throws IOException {
    // canWrite above establishes the converter's supported runtime type.
    ((HttpMessageConverter<Object>) converter)
        .write(body, request.getHeaders().getContentType(), request);
  }
}
