package com.jsav.chat.harness;

import com.jsav.chat.harness.ApiClient.Account;
import java.util.ArrayList;
import java.util.List;

/**
 * El mundo de la corrida: N usuarios en un mismo servidor y un mismo canal. Los correos llevan la
 * hora de la corrida para que correrlo dos veces con la misma semilla no choque con la anterior; el
 * username (máximo 20 caracteres) también. {@code usernames} va en el mismo orden que {@code
 * accounts}: es lo que otro cliente escribe después de un {@code @} para mencionar a este.
 */
public record Setup(List<Account> accounts, List<String> usernames, long serverId, long channelId) {

  public static Setup create(ApiClient api, Scenario sc) {
    String run = Long.toString(System.currentTimeMillis(), 36);
    List<Account> accounts = new ArrayList<>();
    List<String> usernames = new ArrayList<>();
    for (int c = 0; c < sc.clients(); c++) {
      String username = usernameOf(sc, run, c);
      accounts.add(
          api.register(
              "h%d-%s-%d@harness.test".formatted(sc.seed(), run, c),
              "harness-pass-1",
              "Cliente" + c,
              username));
      usernames.add(username);
    }
    Account owner = accounts.get(0);
    long serverId = api.createServer(owner, "Arnes " + sc.seed() + "-" + run);
    long channelId = api.generalChannel(owner, serverId);
    String invite = api.createInvite(owner, serverId);
    for (Account member : accounts.subList(1, accounts.size())) {
      api.acceptInvite(member, invite);
    }
    return new Setup(List.copyOf(accounts), List.copyOf(usernames), serverId, channelId);
  }

  /** Por ejemplo {@code h42mabc123_3}: letras, números y guion bajo, 15 caracteres o menos. */
  static String usernameOf(Scenario sc, String run, int client) {
    return "h%d%s_%d".formatted(Math.floorMod(sc.seed(), 100L), run, client);
  }
}
