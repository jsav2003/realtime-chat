package com.jsav.chat.channel;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChannelRepository extends JpaRepository<Channel, Long> {

  /** Todos los canales del servidor: para owner y admin. */
  List<Channel> findByServerIdOrderById(Long serverId);

  boolean existsByServerIdAndName(Long serverId, String name);
}
