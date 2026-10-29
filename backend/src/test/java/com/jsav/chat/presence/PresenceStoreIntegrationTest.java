package com.jsav.chat.presence;

import static org.assertj.core.api.Assertions.assertThat;

import com.jsav.chat.TestcontainersConfiguration;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * La presencia en Redis real (ADR 0008): varias conexiones por usuario, vencimiento sin avisar y
 * renovación. Los ids de usuario no existen en Postgres: aquí solo se prueba el almacén.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class PresenceStoreIntegrationTest {

  private static final AtomicLong NEXT_USER = new AtomicLong(9_000_000);
  private static final Duration LONG = Duration.ofMinutes(5);

  @Autowired PresenceStore store;

  private static Long freshUser() {
    return NEXT_USER.incrementAndGet();
  }

  @Test
  void un_usuario_sin_conexiones_no_esta_en_linea() {
    assertThat(store.online(List.of(freshUser()))).isEmpty();
  }

  @Test
  void un_usuario_con_una_conexion_esta_en_linea() {
    Long user = freshUser();

    store.touch(user, "a:1", LONG);

    assertThat(store.online(List.of(user))).containsExactly(user);
  }

  @Test
  void con_dos_pestanas_sigue_en_linea_hasta_cerrar_la_ultima() {
    Long user = freshUser();
    store.touch(user, "a:1", LONG);
    store.touch(user, "b:2", LONG);

    store.remove(user, "a:1");
    assertThat(store.online(List.of(user))).containsExactly(user);

    store.remove(user, "b:2");
    assertThat(store.online(List.of(user))).isEmpty();
  }

  @Test
  void una_conexion_que_nadie_renueva_vence_sola() throws Exception {
    Long user = freshUser();
    store.touch(user, "a:1", Duration.ofMillis(300));
    assertThat(store.online(List.of(user))).containsExactly(user);

    Thread.sleep(600);

    assertThat(store.online(List.of(user))).isEmpty();
  }

  @Test
  void renovar_la_conexion_la_mantiene_viva_mas_alla_del_primer_vencimiento() throws Exception {
    Long user = freshUser();
    store.touch(user, "a:1", Duration.ofMillis(500));

    Thread.sleep(300);
    store.touch(user, "a:1", Duration.ofMillis(500));
    Thread.sleep(300);

    // Han pasado 600 ms desde el primer touch (vencía a los 500) y sigue viva por la renovación.
    assertThat(store.online(List.of(user))).containsExactly(user);
  }

  @Test
  void una_conexion_vencida_no_apaga_a_las_que_siguen_vivas() throws Exception {
    Long user = freshUser();
    store.touch(user, "caida:1", Duration.ofMillis(300));
    store.touch(user, "viva:2", LONG);

    Thread.sleep(600);

    assertThat(store.online(List.of(user))).containsExactly(user);
  }

  @Test
  void consulta_a_varios_usuarios_y_devuelve_solo_los_que_estan_en_linea() {
    Long on1 = freshUser();
    Long on2 = freshUser();
    Long off = freshUser();
    store.touch(on1, "a:1", LONG);
    store.touch(on2, "a:2", LONG);

    assertThat(store.online(List.of(on1, off, on2))).containsExactlyInAnyOrder(on1, on2);
  }

  @Test
  void consultar_a_nadie_no_toca_redis() {
    assertThat(store.online(List.of())).isEmpty();
  }

  @Test
  void quitar_una_conexion_que_no_existe_no_falla() {
    store.remove(freshUser(), "nunca:1");
  }
}
