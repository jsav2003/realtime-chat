package com.jsav.chat.server;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface ServerMemberRepository extends JpaRepository<ServerMember, ServerMemberId> {

  interface MemberView {
    Long getUserId();

    String getDisplayName();

    String getRole();
  }

  @Query(
      """
      select u.id as userId, u.displayName as displayName, m.role as role
      from ServerMember m, User u
      where m.id.serverId = :serverId and u.id = m.id.userId
      order by u.id
      """)
  List<MemberView> findMembers(Long serverId);

  @Modifying
  @Query("delete from ServerMember m where m.id.serverId = :serverId and m.id.userId = :userId")
  int deleteMember(Long serverId, Long userId);
}
