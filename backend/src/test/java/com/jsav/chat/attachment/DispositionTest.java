package com.jsav.chat.attachment;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DispositionTest {

  @Test
  void onlyFourImageTypesAreShownInline() {
    for (String type : new String[] {"image/png", "image/jpeg", "image/gif", "image/webp"}) {
      assertThat(Disposition.header("foto", type)).isEqualTo("inline");
      assertThat(Disposition.responseContentType(type)).isEqualTo(type);
    }
  }

  @Test
  void everythingElseIsADownloadAndServedAsBinary() {
    // SVG y HTML pueden ejecutar scripts en el origen del sitio: nunca se muestran.
    for (String type :
        new String[] {"image/svg+xml", "text/html", "application/pdf", "text/plain", "image/bmp"}) {
      assertThat(Disposition.header("x", type)).startsWith("attachment;");
      assertThat(Disposition.responseContentType(type)).isEqualTo("application/octet-stream");
    }
  }

  @Test
  void theFilenameCannotBreakOutOfTheHeader() {
    String header = Disposition.header("a\"b\\c;d%e\r\nX-Evil: 1.pdf", "application/pdf");

    assertThat(header).doesNotContain("\r").doesNotContain("\n");
    assertThat(header).startsWith("attachment; filename=\"a_b_c_d_e__X-Evil: 1.pdf\"");
  }

  @Test
  void nonAsciiNamesGoPercentEncodedInUtf8() {
    String header = Disposition.header("canción año.txt", "text/plain");

    assertThat(header)
        .isEqualTo(
            "attachment; filename=\"canci_n a_o.txt\"; filename*=UTF-8''canci%C3%B3n%20a%C3%B1o.txt");
  }
}
