package com.jsav.chat.attachment;

import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * Cómo el navegador trata un archivo que baja de S3 (ADR 0014). Solo cuatro tipos de imagen se
 * muestran dentro de la página ({@code inline}). Todo lo demás se descarga: un HTML o un SVG subido
 * por un usuario nunca se abre como página del sitio.
 */
final class Disposition {

  /** SVG no está: puede llevar scripts. */
  private static final Set<String> INLINE_IMAGES =
      Set.of("image/png", "image/jpeg", "image/gif", "image/webp");

  private Disposition() {}

  static boolean isInlineImage(String contentType) {
    return INLINE_IMAGES.contains(contentType);
  }

  /** El tipo con el que S3 responde: el declarado para las imágenes, binario para lo demás. */
  static String responseContentType(String contentType) {
    return isInlineImage(contentType) ? contentType : "application/octet-stream";
  }

  /**
   * El encabezado {@code Content-Disposition}: {@code inline} para las imágenes y {@code
   * attachment} con el nombre para el resto. El nombre va dos veces, como pide la RFC 6266: una
   * versión ASCII sin comillas ni barras por si el navegador es viejo, y otra con %XX en UTF-8.
   */
  static String header(String filename, String contentType) {
    if (isInlineImage(contentType)) {
      return "inline";
    }
    StringBuilder ascii = new StringBuilder();
    for (char c : filename.toCharArray()) {
      boolean safe = c >= 0x20 && c < 0x7f && c != '"' && c != '\\' && c != '%' && c != ';';
      ascii.append(safe ? c : '_');
    }
    return "attachment; filename=\"" + ascii + "\"; filename*=UTF-8''" + percentEncode(filename);
  }

  private static String percentEncode(String value) {
    StringBuilder out = new StringBuilder();
    for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
      int c = b & 0xff;
      boolean unreserved =
          (c >= 'a' && c <= 'z')
              || (c >= 'A' && c <= 'Z')
              || (c >= '0' && c <= '9')
              || c == '-'
              || c == '.'
              || c == '_'
              || c == '~';
      if (unreserved) {
        out.append((char) c);
      } else {
        out.append('%').append(String.format("%02X", c));
      }
    }
    return out.toString();
  }
}
