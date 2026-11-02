package com.pocketbanker.perception;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;

/** Bounded read-only collection sync. Redirects and cross-origin resource URLs are rejected. */
final class CalDavClient {
  private final HttpClient http =
      HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(5))
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();

  record Resource(URI uri, String etag) {}

  Map<String, Resource> list(CalDavConnector.Account account) throws Exception {
    URI base = URI.create(account.url());
    var response =
        send(
            account,
            base,
            "PROPFIND",
            """
        <?xml version="1.0" encoding="utf-8"?>
        <d:propfind xmlns:d="DAV:"><d:prop><d:getetag/><d:resourcetype/></d:prop></d:propfind>
        """,
            512_000,
            null);
    if (response.statusCode() != 207) throw invalid();
    var factory = DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(true);
    factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
    factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
    factory.setXIncludeAware(false);
    factory.setExpandEntityReferences(false);
    var builder = factory.newDocumentBuilder();
    builder.setErrorHandler(
        new org.xml.sax.helpers.DefaultHandler() {
          @Override
          public void fatalError(org.xml.sax.SAXParseException e) throws org.xml.sax.SAXException {
            throw e;
          }
        });
    var root = builder.parse(new ByteArrayInputStream(response.body())).getDocumentElement();
    if (!"DAV:".equals(root.getNamespaceURI()) || !"multistatus".equals(root.getLocalName()))
      throw invalid();
    Map<String, Resource> result = new LinkedHashMap<>();
    boolean collectionFound = false;
    for (Element row : children(root, "response")) {
      String href = single(row, "href").getTextContent().strip();
      URI uri = resourceUri(base, href);
      Element props = null;
      for (Element status : children(row, "propstat")) {
        String code = single(status, "status").getTextContent().strip();
        if (code.matches("HTTP/\\d(?:\\.\\d)? 200(?: .*)?")) {
          if (props != null) throw invalid();
          props = single(status, "prop");
        } else if (!code.matches("HTTP/\\d(?:\\.\\d)? 404(?: .*)?")) throw invalid();
      }
      if (props == null) throw invalid();
      boolean isCollection = !children(single(props, "resourcetype"), "collection").isEmpty();
      if (uri.equals(base)) {
        if (!isCollection || collectionFound) throw invalid();
        collectionFound = true;
        continue;
      }
      if (isCollection)
        throw invalid(); // A configured calendar collection contains resources only.
      String etag = single(props, "getetag").getTextContent().strip();
      if (etag.isEmpty() || etag.length() > 512 || etag.contains("\r") || etag.contains("\n"))
        throw invalid();
      String hash = com.pocketbanker.security.PerceptionAccess.hash(uri.toString());
      if (result.put(hash, new Resource(uri, etag)) != null || result.size() > 100) throw invalid();
    }
    if (!collectionFound) throw invalid(); // A partial/error listing can never authorize deletion.
    return result;
  }

  String fetch(CalDavConnector.Account account, Resource resource) throws Exception {
    var response = send(account, resource.uri(), "GET", null, 100_000, resource.etag());
    if (response.statusCode() != 200
        || !response.headers().firstValue("ETag").orElse("").equals(resource.etag()))
      throw invalid();
    return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(response.body())).toString();
  }

  static URI resourceUri(URI base, String href) {
    try {
      URI uri = base.resolve(href).normalize();
      String path = uri.getPath();
      if (!Objects.equals(uri.getScheme(), base.getScheme())
          || !Objects.equals(uri.getRawAuthority(), base.getRawAuthority())
          || uri.getRawQuery() != null
          || uri.getRawFragment() != null
          || uri.getUserInfo() != null
          || path == null
          || path.contains("\\")
          || Arrays.asList(path.split("/")).contains("..")
          || !path.startsWith(base.getPath())) throw invalid();
      String remainder = path.substring(base.getPath().length());
      if (remainder.contains("/")) throw invalid();
      return uri;
    } catch (IllegalArgumentException e) {
      throw invalid();
    }
  }

  private HttpResponse<byte[]> send(
      CalDavConnector.Account account, URI uri, String method, String body, int limit, String etag)
      throws Exception {
    var request =
        HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(10))
            .header(
                "Authorization",
                "Basic "
                    + Base64.getEncoder()
                        .encodeToString(
                            (account.username() + ":" + account.password())
                                .getBytes(StandardCharsets.UTF_8)))
            .header("Accept-Encoding", "identity")
            .method(
                method,
                body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body));
    if (body != null)
      request.header("Content-Type", "application/xml; charset=utf-8").header("Depth", "1");
    if (etag != null && !etag.startsWith("W/")) request.header("If-Match", etag);
    var future = http.sendAsync(request.build(), info -> new LimitedBody(limit));
    try {
      return future.get(15, TimeUnit.SECONDS);
    } finally {
      if (!future.isDone()) future.cancel(true);
    }
  }

  private static List<Element> children(Element parent, String name) {
    List<Element> result = new ArrayList<>();
    for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling())
      if (node instanceof Element element
          && "DAV:".equals(element.getNamespaceURI())
          && name.equals(element.getLocalName())) result.add(element);
    return result;
  }

  private static Element single(Element parent, String name) {
    var matches = children(parent, name);
    if (matches.size() != 1) throw invalid();
    return matches.get(0);
  }

  private static PerceptionException invalid() {
    return new PerceptionException(502, "invalid_caldav_response");
  }

  private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
    final CompletableFuture<byte[]> result = new CompletableFuture<>();
    final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    final int limit;
    Flow.Subscription subscription;

    LimitedBody(int limit) {
      this.limit = limit;
    }

    @Override
    public CompletionStage<byte[]> getBody() {
      return result;
    }

    @Override
    public void onSubscribe(Flow.Subscription subscription) {
      this.subscription = subscription;
      subscription.request(Long.MAX_VALUE);
    }

    @Override
    public void onNext(List<ByteBuffer> buffers) {
      for (var buffer : buffers) {
        if (buffer.remaining() > limit - bytes.size()) {
          subscription.cancel();
          result.completeExceptionally(invalid());
          return;
        }
        byte[] chunk = new byte[buffer.remaining()];
        buffer.get(chunk);
        bytes.writeBytes(chunk);
      }
    }

    @Override
    public void onError(Throwable error) {
      result.completeExceptionally(error);
    }

    @Override
    public void onComplete() {
      result.complete(bytes.toByteArray());
    }
  }
}
