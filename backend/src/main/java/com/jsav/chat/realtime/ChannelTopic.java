package com.jsav.chat.realtime;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Los únicos destinos a los que un cliente puede suscribirse: {@code /topic/channel.{id}} (los
 * mensajes) y {@code /topic/channel.{id}.typing} (quién está escribiendo). Cualquier otra cosa
 * (otro prefijo, comodines, ids que no son números) no se reconoce y se rechaza.
 *
 * <p>Son claves de ruteo distintas en RabbitMQ: quien se suscribe a uno no recibe lo del otro.
 */
public final class ChannelTopic {

  private static final Pattern DESTINATION =
      Pattern.compile("^/topic/channel[.]([1-9][0-9]{0,17})([.]typing)?$");

  private ChannelTopic() {}

  /** El destino al que se publican los mensajes de un canal. */
  public static String of(long channelId) {
    return WebSocketConfig.TOPIC_PREFIX + "/channel." + channelId;
  }

  /** El destino al que se publica quién está escribiendo en un canal. */
  public static String typingOf(long channelId) {
    return of(channelId) + ".typing";
  }

  /** El id del canal si {@code destination} es exactamente un destino de canal. */
  public static Optional<Long> parse(String destination) {
    if (destination == null) {
      return Optional.empty();
    }
    Matcher m = DESTINATION.matcher(destination);
    return m.matches() ? Optional.of(Long.parseLong(m.group(1))) : Optional.empty();
  }
}
