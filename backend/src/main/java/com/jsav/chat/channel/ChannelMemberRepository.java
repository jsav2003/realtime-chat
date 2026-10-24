package com.jsav.chat.channel;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface ChannelMemberRepository extends JpaRepository<ChannelMember, ChannelMemberId> {

  /** Idempotente: agregar a quien ya está no es un error. */
  @Modifying
  @Query(
      value =
          "insert into channel_members (channel_id, user_id) values (:channelId, :userId)"
              + " on conflict do nothing",
      nativeQuery = true)
  int add(Long channelId, Long userId);

  @Modifying
  @Query(
      "delete from ChannelMember cm where cm.id.channelId = :channelId and cm.id.userId = :userId")
  int remove(Long channelId, Long userId);

  /** Al expulsar: saca al usuario de todos los canales privados de ese servidor. */
  @Modifying
  @Query(
      """
      delete from ChannelMember cm
      where cm.id.userId = :userId
        and cm.id.channelId in (select c.id from Channel c where c.serverId = :serverId)
      """)
  int removeFromServer(Long serverId, Long userId);
}
