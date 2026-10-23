package com.jsav.chat.server;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ServerRepository extends JpaRepository<ChatServer, Long> {

  @Query(
      """
      select s from ChatServer s, ServerMember m
      where m.id.serverId = s.id and m.id.userId = :userId
      order by s.id
      """)
  List<ChatServer> findByMember(Long userId);
}
