package org.example.server.persistence.repository;

import org.example.server.persistence.entity.UserEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface UserRepository extends JpaRepository<UserEntity, Integer> {
    boolean existsByUsernameIgnoreCase(String username);
    boolean existsByEmailIgnoreCase(String email);
    boolean existsByPhone(String phone);

    @Query("""
        select u from UserEntity u
        where (lower(u.username)=lower(:identity)
            or lower(u.email)=lower(:identity)
            or (u.phone is not null and (u.phone=:identity or u.phone=:digits)))
          and u.active=1 and coalesce(u.locked,0)=0
        """)
    Optional<UserEntity> findActiveByIdentity(@Param("identity") String identity, @Param("digits") String digits);

    default Optional<UserEntity> findActiveByIdentity(String identity) {
        return findActiveByIdentity(identity, cleanDigits(identity));
    }

    @Query("""
        select u from UserEntity u
        where (lower(u.username)=lower(:identity)
            or lower(u.email)=lower(:identity)
            or (u.phone is not null and (u.phone=:identity or u.phone=:digits)))
          and u.active=1
        """)
    Optional<UserEntity> findActiveByIdentityIncludingLocked(@Param("identity") String identity, @Param("digits") String digits);

    default Optional<UserEntity> findActiveByIdentityIncludingLocked(String identity) {
        return findActiveByIdentityIncludingLocked(identity, cleanDigits(identity));
    }

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select u from UserEntity u
        where (lower(u.username)=lower(:identity)
            or lower(u.email)=lower(:identity)
            or (u.phone is not null and (u.phone=:identity or u.phone=:digits)))
          and u.active=1
        """)
    Optional<UserEntity> findForAuthentication(@Param("identity") String identity, @Param("digits") String digits);

    default Optional<UserEntity> findForAuthentication(String identity) {
        return findForAuthentication(identity, cleanDigits(identity));
    }

    static String cleanDigits(String text) {
        if (text == null) return "";
        String d = text.replaceAll("[^0-9]", "");
        return d.isBlank() ? text : d;
    }

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from UserEntity u where u.id=:id")
    Optional<UserEntity> findByIdForAuthentication(@Param("id") Integer id);
}
