package com.agenticauth.security;

import java.util.stream.Collectors;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import com.agenticauth.domain.Member;
import com.agenticauth.dto.MemberDTO;
import com.agenticauth.repository.MemberRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;

@Service
@Log4j2
@RequiredArgsConstructor
public class CustomUserDetailsService implements UserDetailsService{

  private final MemberRepository memberRepository;

  @Override
  public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
    
    log.info("----------------loadUserByUsername-----------------------------");

Member member = memberRepository.getWithRoles(username);

    if(member == null){
      throw new UsernameNotFoundException("Not Found");
    }

    MemberDTO memberDTO = new MemberDTO(
            member.getEmail(),
            member.getPw(),
            member.getNickname(),
            member.isSocial(),
            member.getMemberRoleList()
                  .stream()
                  .map(memberRole -> memberRole.name()).collect(Collectors.toList()));

    log.info(memberDTO);

    return memberDTO;


  }
  
}