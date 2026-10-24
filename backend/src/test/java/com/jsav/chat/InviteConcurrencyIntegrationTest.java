package com.jsav.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.jsav.chat.common.NotFoundException;
import com.jsav.chat.invite.InviteDtos.CreateInviteRequest;
import com.jsav.chat.invite.InviteRepository;
import com.jsav.chat.invite.InviteService;
import com.jsav.chat.server.ServerDtos.CreateServerRequest;
import com.jsav.chat.server.ServerMemberRepository;
import com.jsav.chat.server.ServerService;
import com.jsav.chat.user.User;
import com.jsav.chat.user.UserRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/** El límite de usos de una invitación se respeta aunque todos acepten en el mismo instante. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class InviteConcurrencyIntegrationTest {

  private static final AtomicInteger RUNS = new AtomicInteger();

  @Autowired UserRepository users;
  @Autowired ServerService serverService;
  @Autowired InviteService inviteService;
  @Autowired InviteRepository invites;
  @Autowired ServerMemberRepository members;

  @Test
  void maxUsesOne_withTenSimultaneousAccepts_letsExactlyOneIn() throws Exception {
    assertOnlyAllowedEnter(1, 10);
  }

  @Test
  void maxUsesThree_withTenSimultaneousAccepts_letsExactlyThreeIn() throws Exception {
    assertOnlyAllowedEnter(3, 10);
  }

  private void assertOnlyAllowedEnter(int maxUses, int contenders) throws Exception {
    int run = RUNS.incrementAndGet();
    User owner = users.save(new User("conc" + run + "-owner@x.test", "x", "Owner"));
    Long serverId =
        serverService.create(owner.getId(), new CreateServerRequest("Concurrente" + run)).id();
    String code =
        inviteService
            .create(owner.getId(), serverId, new CreateInviteRequest(null, maxUses))
            .code();

    List<User> joiners = new ArrayList<>();
    for (int i = 0; i < contenders; i++) {
      joiners.add(users.save(new User("conc" + run + "-" + i + "@x.test", "x", "U" + i)));
    }

    ExecutorService pool = Executors.newFixedThreadPool(contenders);
    CountDownLatch ready = new CountDownLatch(contenders);
    CountDownLatch go = new CountDownLatch(1);
    List<Future<Boolean>> results = new ArrayList<>();
    for (User u : joiners) {
      Callable<Boolean> attempt =
          () -> {
            ready.countDown();
            go.await();
            try {
              inviteService.accept(u.getId(), code);
              return true;
            } catch (NotFoundException e) {
              return false;
            }
          };
      results.add(pool.submit(attempt));
    }
    ready.await();
    go.countDown();

    int accepted = 0;
    for (Future<Boolean> f : results) {
      if (f.get()) {
        accepted++;
      }
    }
    pool.shutdown();

    assertThat(accepted).isEqualTo(maxUses);
    assertThat(invites.findById(code).orElseThrow().getUses()).isEqualTo(maxUses);
    // El owner más los que entraron.
    assertThat(members.findMembers(serverId)).hasSize(1 + maxUses);
  }
}
