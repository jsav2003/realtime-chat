package com.jsav.chat.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jsav.chat.TestcontainersConfiguration;
import com.jsav.chat.channel.Channel;
import com.jsav.chat.channel.ChannelDtos.CreateChannelRequest;
import com.jsav.chat.channel.ChannelRepository;
import com.jsav.chat.channel.ChannelService;
import com.jsav.chat.common.NotFoundException;
import com.jsav.chat.server.Role;
import com.jsav.chat.server.ServerDtos.CreateServerRequest;
import com.jsav.chat.server.ServerMember;
import com.jsav.chat.server.ServerMemberRepository;
import com.jsav.chat.server.ServerService;
import com.jsav.chat.user.User;
import com.jsav.chat.user.UserRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * {@link AccessPolicy#readableChannels} es {@link AccessPolicy#requireChannel} aplicada a todos los
 * canales de golpe, y no una segunda copia de la regla. Esta prueba lo vigila: para cada persona y
 * cada canal del servidor, el canal está en la lista <b>si y solo si</b> {@code can(READ)} lo deja
 * pasar. Si alguien cambia una de las dos reglas y olvida la otra, esto falla (la búsqueda y los no
 * leídos se apoyan en la lista).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class AccessPolicyConsistencyTest {

  private static final AtomicInteger RUNS = new AtomicInteger();

  @Autowired AccessPolicy policy;
  @Autowired UserRepository users;
  @Autowired ServerMemberRepository serverMembers;
  @Autowired ServerService serverService;
  @Autowired ChannelService channelService;
  @Autowired ChannelRepository channelRepository;

  /** Cada persona con un nombre que dice qué es. */
  Map<String, User> people = new LinkedHashMap<>();

  Long serverId;
  Long inSecret;

  @BeforeEach
  void setUp() {
    int run = RUNS.incrementAndGet();
    people.clear();
    for (String name : List.of("owner", "admin", "inSecret", "plainMember", "outsider", "kicked")) {
      people.put(
          name,
          users.save(new User("ac" + run + "-" + name + "@x.test", "x", name, "ac" + run + name)));
    }
    serverId =
        serverService.create(people.get("owner").getId(), new CreateServerRequest("Ac" + run)).id();
    serverMembers.save(new ServerMember(serverId, id("admin"), Role.ADMIN));
    serverMembers.save(new ServerMember(serverId, id("inSecret"), Role.MEMBER));
    serverMembers.save(new ServerMember(serverId, id("plainMember"), Role.MEMBER));
    serverMembers.save(new ServerMember(serverId, id("kicked"), Role.MEMBER));

    Long secret = createChannel("secreto", "private");
    createChannel("otro-publico", "text");
    createChannel("solo-owner", "private");
    channelService.addMember(id("owner"), secret, id("inSecret"));
    inSecret = secret;

    serverService.kick(id("owner"), serverId, id("kicked"));
  }

  private Long id(String person) {
    return people.get(person).getId();
  }

  private Long createChannel(String name, String kind) {
    return channelService.create(id("owner"), serverId, new CreateChannelRequest(name, kind)).id();
  }

  @Test
  void readableChannels_matchesCanRead_forEveryPersonAndEveryChannel() {
    List<Channel> all = channelRepository.findByServerIdOrderById(serverId);
    assertThat(all).hasSize(4); // #general, secreto, otro-publico, solo-owner

    for (String person : people.keySet()) {
      List<Long> readable;
      try {
        readable =
            policy.readableChannels(id(person), serverId).stream().map(Channel::getId).toList();
      } catch (NotFoundException e) {
        readable = List.of(); // no es miembro del servidor: no lee ninguno
      }
      for (Channel c : all) {
        assertThat(readable.contains(c.getId()))
            .as("%s → #%s", person, c.getName())
            .isEqualTo(policy.can(id(person), c.getId(), ChannelAction.READ));
      }
    }
  }

  @Test
  void readableChannels_hasTheExpectedShape() {
    assertThat(names("owner")).containsExactly("general", "secreto", "otro-publico", "solo-owner");
    assertThat(names("admin")).containsExactly("general", "secreto", "otro-publico", "solo-owner");
    assertThat(names("inSecret")).containsExactly("general", "secreto", "otro-publico");
    assertThat(names("plainMember")).containsExactly("general", "otro-publico");
    assertThat(inSecret).isNotNull();
  }

  @Test
  void aStrangerAndAKickedMember_getNotFound() {
    assertThatThrownBy(() -> policy.readableChannels(id("outsider"), serverId))
        .isInstanceOf(NotFoundException.class);
    assertThatThrownBy(() -> policy.readableChannels(id("kicked"), serverId))
        .isInstanceOf(NotFoundException.class);
  }

  private List<String> names(String person) {
    return policy.readableChannels(id(person), serverId).stream().map(Channel::getName).toList();
  }
}
