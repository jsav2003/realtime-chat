package com.jsav.chat.server;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ServerMemberRepository extends JpaRepository<ServerMember, ServerMemberId> {}
