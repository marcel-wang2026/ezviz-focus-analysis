package com.focus.ezviz.repository;

import com.focus.ezviz.domain.EzvizAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EzvizAccountRepository extends JpaRepository<EzvizAccount, Long> {
    List<EzvizAccount> findByEnabledTrueOrderByIdAsc();
}
