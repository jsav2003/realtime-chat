package com.jsav.chat.invite;

import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface InviteRepository extends JpaRepository<Invite, String> {

  /** Invitación que todavía se puede usar: ni vencida ni agotada. */
  @Query(
      """
      select i from Invite i
      where i.code = :code
        and (i.expiresAt is null or i.expiresAt > :now)
        and (i.maxUses is null or i.uses < i.maxUses)
      """)
  Optional<Invite> findUsable(String code, Instant now);
}
