package com.jsav.chat.harness;

import com.jayway.jsonpath.JsonPath;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * La API REST del backend, vista desde el arnés. Todo pasa por el balanceador, igual que un cliente
 * de verdad: si una instancia está reiniciando, el balanceador usa la otra o responde un error, y
 * el arnés reintenta con el mismo {@code clientMsgId}.
 */
public final class ApiClient {

  /** Un usuario ya registrado, con su token. */
  public record Account(long id, String email, String token) {}

  /** Un mensaje guardado, tal como lo devuelve el servidor. */
  public record Stored(long seq, UUID clientMsgId, String body) {}

  /** Respuesta de un envío: 201 si es nuevo, 200 si es un reintento ya guardado. */
  public record SendResult(int status, Stored message) {}

  /** El servidor respondió, pero no con éxito. */
  public static final class ApiException extends RuntimeException {
    public final int status;

    /** Lo que pidió el servidor en {@code Retry-After}, en segundos; 0 si no mandó el header. */
    public final long retryAfterSeconds;

    ApiException(int status, String body) {
      this(status, body, 0);
    }

    ApiException(int status, String body, long retryAfterSeconds) {
      super("HTTP " + status + ": " + body);
      this.status = status;
      this.retryAfterSeconds = retryAfterSeconds;
    }
  }

  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  private final String base;

  public ApiClient(String base) {
    this.base = base;
  }

  public Account register(String email, String password, String displayName) {
    String json =
        post(
            null,
            "/auth/register",
            "{\"email\":\"%s\",\"password\":\"%s\",\"displayName\":\"%s\"}"
                .formatted(email, password, displayName));
    return new Account(
        ((Number) JsonPath.read(json, "$.user.id")).longValue(),
        email,
        JsonPath.read(json, "$.token"));
  }

  public long createServer(Account owner, String name) {
    String json = post(owner.token(), "/servers", "{\"name\":\"%s\"}".formatted(name));
    return ((Number) JsonPath.read(json, "$.id")).longValue();
  }

  /** El canal público que trae cada servidor nuevo. */
  public long generalChannel(Account who, long serverId) {
    String json = get(who.token(), "/servers/" + serverId + "/channels");
    return ((Number) JsonPath.read(json, "$[0].id")).longValue();
  }

  public String createInvite(Account owner, long serverId) {
    String json = post(owner.token(), "/servers/" + serverId + "/invites", "{}");
    return JsonPath.read(json, "$.code");
  }

  public void acceptInvite(Account who, String code) {
    post(who.token(), "/invites/" + code + "/accept", null);
  }

  /** Envía un mensaje. Lanza {@link ApiException} si el servidor responde un error. */
  public SendResult send(Account who, long channelId, UUID clientMsgId, String body) {
    HttpResponse<String> res =
        exchange(
            request(who.token(), "/channels/" + channelId + "/messages")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        "{\"clientMsgId\":\"%s\",\"body\":\"%s\"}".formatted(clientMsgId, body)))
                .build());
    if (res.statusCode() != 200 && res.statusCode() != 201) {
      throw new ApiException(res.statusCode(), res.body(), retryAfterSeconds(res));
    }
    return new SendResult(res.statusCode(), parse(res.body()));
  }

  private static long retryAfterSeconds(HttpResponse<?> res) {
    return res.headers()
        .firstValue("Retry-After")
        .map(
            v -> {
              try {
                return Long.parseLong(v.trim());
              } catch (NumberFormatException e) {
                return 0L;
              }
            })
        .orElse(0L);
  }

  /** Todo lo posterior a {@code afterSeq}, página por página. */
  public List<Stored> listAfter(Account who, long channelId, long afterSeq) {
    List<Stored> all = new ArrayList<>();
    long after = afterSeq;
    while (true) {
      String json =
          get(
              who.token(),
              "/channels/" + channelId + "/messages?after_seq=" + after + "&limit=100");
      List<Map<String, Object>> page = JsonPath.read(json, "$.messages");
      for (Map<String, Object> m : page) {
        all.add(
            new Stored(
                ((Number) m.get("seq")).longValue(),
                UUID.fromString((String) m.get("clientMsgId")),
                (String) m.get("body")));
      }
      boolean hasMore = JsonPath.read(json, "$.hasMore");
      if (!hasMore || page.isEmpty()) {
        return all;
      }
      after = all.get(all.size() - 1).seq();
    }
  }

  private static Stored parse(String json) {
    return new Stored(
        ((Number) JsonPath.read(json, "$.seq")).longValue(),
        UUID.fromString(JsonPath.read(json, "$.clientMsgId")),
        JsonPath.read(json, "$.body"));
  }

  private String get(String token, String path) {
    HttpResponse<String> res = exchange(request(token, path).GET().build());
    return okBody(res);
  }

  private String post(String token, String path, String json) {
    HttpRequest.BodyPublisher body =
        json == null
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(json);
    return okBody(exchange(request(token, path).POST(body).build()));
  }

  private HttpRequest.Builder request(String token, String path) {
    HttpRequest.Builder b =
        HttpRequest.newBuilder(URI.create(base + path))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json");
    if (token != null) {
      b.header("Authorization", "Bearer " + token);
    }
    return b;
  }

  private HttpResponse<String> exchange(HttpRequest req) {
    try {
      return http.send(req, HttpResponse.BodyHandlers.ofString());
    } catch (IOException e) {
      throw new IllegalStateException("Sin respuesta de " + req.uri() + ": " + e, e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrumpido", e);
    }
  }

  private static String okBody(HttpResponse<String> res) {
    if (res.statusCode() >= 300) {
      throw new ApiException(res.statusCode(), res.body());
    }
    return res.body();
  }
}
