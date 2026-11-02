package com.jsav.chat.mention;

import com.jsav.chat.access.AccessPolicy;
import com.jsav.chat.access.ChannelAction;
import com.jsav.chat.user.UserRepository;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Decide a quién hay que notificar por un mensaje (ADR 0011). Un {@code @usuario} solo notifica si
 * la persona existe, no es quien escribe y <b>puede ver el canal</b>: avisarle a alguien que no ve
 * un canal privado revelaría que el mensaje existe. La pregunta de acceso la responde {@link
 * AccessPolicy}, como en el resto de la aplicación, no una regla copiada aquí.
 *
 * <p>Quien escribe no se entera de a cuántos o a quiénes alcanzó: un {@code @usuario} que no
 * notifica a nadie se ve igual que uno que sí.
 */
@Component
public class MentionResolver {

  private final UserRepository users;
  private final AccessPolicy policy;

  public MentionResolver(UserRepository users, AccessPolicy policy) {
    this.users = users;
    this.policy = policy;
  }

  /**
   * Los ids de quienes hay que notificar, en orden. Sin {@code @} en el texto no hace ninguna
   * consulta, que es el caso de casi todos los mensajes.
   */
  public List<Long> recipients(Long authorId, Long channelId, String body) {
    List<String> usernames = MentionParser.usernamesIn(body);
    if (usernames.isEmpty()) {
      return List.of();
    }
    return users.findIdsByUsernames(usernames).stream()
        .filter(id -> !id.equals(authorId))
        .filter(id -> policy.can(id, channelId, ChannelAction.READ))
        .sorted()
        .toList();
  }
}
