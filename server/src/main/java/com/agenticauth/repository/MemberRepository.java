package com.agenticauth.repository;

import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import com.agenticauth.domain.Member;

public interface MemberRepository extends JpaRepository<Member, String> {

  @EntityGraph(attributePaths = {"memberRoleList"})
  @Query("select m from Member m where m.email = :email")
  Member getWithRoles(@Param("email") String email);

}