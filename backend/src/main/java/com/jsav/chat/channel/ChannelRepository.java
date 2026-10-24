package com.jsav.chat.channel;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ChannelRepository extends JpaRepository<Channel, Long> {

  /** Todos los canales del servidor: para owner y admin. */
  List<Channel> findByServerIdOrderById(Long serverId);

  /** Los públicos, más los privados donde el usuario está en {@code channel_members}. */
  @Query(
      """
      select c from Channel c
      where c.serverId = :serverId
        and (c.kind = 'text'
             or exists (select 1 from ChannelMember cm
                        where cm.id.channelId = c.id and cm.id.userId = :userId))
      order by c.id
      """)
  List<Channel> findVisibleTo(Long serverId, Long userId);

  boolean existsByServerIdAndName(Long serverId, String name);
}
