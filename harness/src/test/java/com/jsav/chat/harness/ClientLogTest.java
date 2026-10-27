package com.jsav.chat.harness;

import static org.assertj.core.api.Assertions.assertThat;

import com.jsav.chat.harness.ApiClient.Stored;
import com.jsav.chat.harness.ClientLog.Source;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ClientLogTest {

  private static Stored msg(long seq, UUID id) {
    return new Stored(seq, id, "m" + seq);
  }

  @Test
  void keepsMessagesOrderedBySeq_whateverTheArrivalOrder() {
    ClientLog log = new ClientLog();
    log.receive(msg(3, UUID.randomUUID()), Source.LIVE);
    log.receive(msg(1, UUID.randomUUID()), Source.RESYNC);
    log.receive(msg(2, UUID.randomUUID()), Source.LIVE);

    assertThat(log.messages()).extracting(Stored::seq).containsExactly(1L, 2L, 3L);
    assertThat(log.lastSeq()).isEqualTo(3);
  }

  @Test
  void aRepeatedSeqIsDiscardedAndCounted() {
    ClientLog log = new ClientLog();
    UUID id = UUID.randomUUID();
    log.receive(msg(1, id), Source.LIVE);
    log.receive(msg(1, id), Source.RESYNC);

    assertThat(log.messages()).hasSize(1);
    assertThat(log.repeatedDeliveries()).isEqualTo(1);
    assertThat(log.conflicts()).isEmpty();
  }

  @Test
  void aSeqThatArrivesWithAnotherMessageIsAConflict() {
    ClientLog log = new ClientLog();
    log.receive(msg(1, UUID.randomUUID()), Source.LIVE);
    log.receive(msg(1, UUID.randomUUID()), Source.RESYNC);

    assertThat(log.conflicts()).hasSize(1);
    assertThat(log.messages()).hasSize(1);
  }

  @Test
  void remembersWhichPathDeliveredEachMessageFirst() {
    ClientLog log = new ClientLog();
    log.receive(msg(1, UUID.randomUUID()), Source.LIVE);
    log.receive(msg(2, UUID.randomUUID()), Source.RESYNC);
    log.receive(msg(2, UUID.randomUUID()), Source.LIVE);
    log.receive(msg(3, UUID.randomUUID()), Source.RESYNC);

    assertThat(log.countFirstSeenBy(Source.LIVE)).isEqualTo(1);
    assertThat(log.countFirstSeenBy(Source.RESYNC)).isEqualTo(2);
  }

  @Test
  void anEmptyLogHasLastSeqZero() {
    assertThat(new ClientLog().lastSeq()).isZero();
  }
}
