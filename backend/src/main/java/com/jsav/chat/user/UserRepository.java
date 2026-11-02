package com.jsav.chat.user;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface UserRepository extends JpaRepository<User, Long> {

  @Query("select u from User u where lower(u.email) = lower(:email)")
  Optional<User> findByEmail(String email);

  @Query("select count(u) > 0 from User u where lower(u.email) = lower(:email)")
  boolean existsByEmail(String email);

  @Query("select count(u) > 0 from User u where lower(u.username) = lower(:username)")
  boolean existsByUsername(String username);

  /** Los ids de quienes tienen alguno de estos usernames. {@code usernames} va en minúsculas. */
  @Query("select u.id from User u where lower(u.username) in :usernames")
  List<Long> findIdsByUsernames(Collection<String> usernames);
}
