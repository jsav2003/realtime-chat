package com.jsav.chat.mention;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lee del texto de un mensaje los {@code @usuario} que menciona (ADR 0011).
 *
 * <p>Un handle son de 3 a 20 letras ASCII, números o guion bajo, igual que el username que se pide
 * al registrarse. Dos reglas de borde evitan falsos positivos: el {@code @} no puede ir pegado a
 * una letra, un número, un guion bajo ni a otro {@code @} (así {@code ana@correo.com} no menciona a
 * nadie), y el handle no puede seguir con más caracteres de palabra (así {@code @betoñ} no menciona
 * a {@code beto}, ni uno de 21 caracteres a uno de 20).
 *
 * <p>La misma regla está en el frontend ({@code lib/mentions.ts}) para resaltar los nombres; si se
 * cambia una, hay que cambiar la otra. Los vectores de {@code MentionParserTest} sirven para las
 * dos.
 */
public final class MentionParser {

  /**
   * Cuántos handles distintos se leen de un mensaje. Acota el trabajo de resolverlos (cada uno
   * puede costar consultas de acceso) y el reparto de notificaciones que un solo mensaje provoca.
   */
  public static final int MAX_MENTIONS = 10;

  private static final Pattern MENTION =
      Pattern.compile("(?<![\\p{L}\\p{N}_@])@([A-Za-z0-9_]{3,20})(?![\\p{L}\\p{N}_])");

  private MentionParser() {}

  /**
   * Los usernames mencionados, en minúsculas, sin repetir y en el orden en que aparecen. Si hay más
   * de {@link #MAX_MENTIONS}, solo se devuelven los primeros.
   */
  public static List<String> usernamesIn(String body) {
    Set<String> found = new LinkedHashSet<>();
    Matcher m = MENTION.matcher(body);
    while (found.size() < MAX_MENTIONS && m.find()) {
      found.add(m.group(1).toLowerCase(Locale.ROOT));
    }
    return new ArrayList<>(found);
  }
}
