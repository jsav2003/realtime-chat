package com.jsav.chat.harness;

import static org.assertj.core.api.Assertions.assertThat;

import com.jsav.chat.harness.ScenarioPlan.Send;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MentionPlanTest {

  private static ScenarioPlan basePlan(long seed, int clients, int messages) {
    Scenario d = Scenario.DEFAULTS;
    return ScenarioPlan.generate(
        new Scenario(
            seed,
            clients,
            messages,
            0.15,
            0,
            0.2,
            d.paceMillis(),
            d.lbUrl(),
            d.instanceAUrl(),
            d.instanceBUrl()));
  }

  private static List<String> usernames(int clients) {
    List<String> names = new ArrayList<>();
    for (int i = 0; i < clients; i++) {
      names.add("user" + i);
    }
    return names;
  }

  private static List<Send> allSends(MentionPlan plan, int clients) {
    List<Send> all = new ArrayList<>();
    for (int c = 0; c < clients; c++) {
      all.addAll(plan.sendsOf(c));
    }
    return all;
  }

  @Test
  void theSameSeedGivesTheSamePlan() {
    MentionPlan a = MentionPlan.generate(basePlan(42, 6, 30), usernames(6), 0.7);
    MentionPlan b = MentionPlan.generate(basePlan(42, 6, 30), usernames(6), 0.7);

    assertThat(allSends(a, 6)).isEqualTo(allSends(b, 6));
    assertThat(a.plannedMentions()).isEqualTo(b.plannedMentions());
  }

  @Test
  void aDifferentSeedGivesADifferentPlan() {
    MentionPlan a = MentionPlan.generate(basePlan(42, 6, 30), usernames(6), 0.7);
    MentionPlan b = MentionPlan.generate(basePlan(43, 6, 30), usernames(6), 0.7);

    assertThat(allSends(a, 6).stream().map(Send::body).toList())
        .isNotEqualTo(allSends(b, 6).stream().map(Send::body).toList());
  }

  @Test
  void nobodyMentionsThemselves_andNoMessageMentionsMoreThanTwoPeople() {
    int clients = 8;
    MentionPlan plan = MentionPlan.generate(basePlan(7, clients, 80), usernames(clients), 1.0);

    for (int c = 0; c < clients; c++) {
      for (Send s : plan.sendsOf(c)) {
        List<Integer> who = plan.mentionedBy(s.clientMsgId());
        assertThat(who).doesNotContain(c).doesNotHaveDuplicates().hasSizeBetween(1, 2);
      }
    }
  }

  @Test
  void theMentionedUsernamesAppearInTheBody_andNobodyElses() {
    int clients = 5;
    MentionPlan plan = MentionPlan.generate(basePlan(3, clients, 40), usernames(clients), 1.0);

    for (int c = 0; c < clients; c++) {
      for (Send s : plan.sendsOf(c)) {
        List<Integer> who = plan.mentionedBy(s.clientMsgId());
        for (int other = 0; other < clients; other++) {
          assertThat(s.body().contains("@user" + other + " ") || s.body().endsWith("@user" + other))
              .as("¿el cuerpo «%s» menciona a user%d?", s.body(), other)
              .isEqualTo(who.contains(other));
        }
      }
    }
  }

  @Test
  void theMentionRateIsRespectedRoughly() {
    int clients = 8;
    ScenarioPlan base = basePlan(11, clients, 200);
    MentionPlan plan = MentionPlan.generate(base, usernames(clients), 0.7);

    long withMentions =
        allSends(plan, clients).stream()
            .filter(s -> !plan.mentionedBy(s.clientMsgId()).isEmpty())
            .count();
    double share = withMentions / (double) (clients * 200);

    assertThat(share).isBetween(0.62, 0.78);
  }

  @Test
  void aRateOfZeroMentionsNobody_andLeavesTheBodiesAsTheyWere() {
    ScenarioPlan base = basePlan(5, 4, 20);
    MentionPlan plan = MentionPlan.generate(base, usernames(4), 0.0);

    assertThat(plan.plannedMentions()).isZero();
    for (int c = 0; c < 4; c++) {
      assertThat(plan.sendsOf(c)).isEqualTo(base.sendsOf(c));
    }
  }

  @Test
  void onlyTheBodyChanges_theRestOfEachSendIsTheBasePlans() {
    ScenarioPlan base = basePlan(9, 4, 25);
    MentionPlan plan = MentionPlan.generate(base, usernames(4), 0.7);

    for (int c = 0; c < 4; c++) {
      List<Send> baseSends = base.sendsOf(c);
      List<Send> mine = plan.sendsOf(c);
      assertThat(mine).hasSameSizeAs(baseSends);
      for (int i = 0; i < mine.size(); i++) {
        Send a = baseSends.get(i);
        Send b = mine.get(i);
        assertThat(b.clientMsgId()).isEqualTo(a.clientMsgId());
        assertThat(b.pauseMillis()).isEqualTo(a.pauseMillis());
        assertThat(b.resendAfterConfirm()).isEqualTo(a.resendAfterConfirm());
        assertThat(b.body()).startsWith(a.body());
      }
    }
  }

  @Test
  void anUnknownMessageMentionsNobody() {
    MentionPlan plan = MentionPlan.generate(basePlan(1, 3, 5), usernames(3), 1.0);

    assertThat(plan.mentionedBy(UUID.randomUUID())).isEmpty();
  }
}
