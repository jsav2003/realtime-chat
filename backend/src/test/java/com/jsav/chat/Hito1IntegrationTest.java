package com.jsav.chat;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** Postgres real (Testcontainers) con las migraciones de Flyway aplicadas. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class Hito1IntegrationTest {

  @Autowired MockMvc mvc;

  private String register(String email) throws Exception {
    String body =
        mvc.perform(
                post("/auth/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"email\":\""
                            + email
                            + "\",\"password\":\"password1\",\"displayName\":\"Test\"}"))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return JsonPath.read(body, "$.token");
  }

  @Test
  void registerLoginCreateAndListServers_andOtherUserSeesNothing() throws Exception {
    String ana = register("ana@x.com");
    String beto = register("beto@x.com");

    mvc.perform(
            post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"ANA@x.com\",\"password\":\"password1\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.token").isNotEmpty());

    mvc.perform(
            post("/servers")
                .header("Authorization", "Bearer " + ana)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Mi servidor\"}"))
        .andExpect(status().isCreated());

    mvc.perform(get("/servers").header("Authorization", "Bearer " + ana))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].name").value("Mi servidor"));

    mvc.perform(get("/servers").header("Authorization", "Bearer " + beto))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
  }

  @Test
  void duplicateEmailIgnoringCase_returns409() throws Exception {
    register("dup@x.com");

    mvc.perform(
            post("/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"email\":\"DUP@x.com\",\"password\":\"password1\",\"displayName\":\"X\"}"))
        .andExpect(status().isConflict());
  }

  @Test
  void wrongPassword_returns401_andNoTokenMeans401() throws Exception {
    register("clave@x.com");

    mvc.perform(
            post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"clave@x.com\",\"password\":\"incorrecta\"}"))
        .andExpect(status().isUnauthorized());

    mvc.perform(get("/servers")).andExpect(status().isUnauthorized());
  }
}
