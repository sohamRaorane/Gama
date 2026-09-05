package com.meshpay.auth.repository;

import com.meshpay.auth.entity.Bridge;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface BridgeRepository extends JpaRepository<Bridge, UUID> {

    Optional<Bridge> findByBridgeId(String bridgeId);

    Optional<Bridge> findByCredentialIdentifier(String credentialIdentifier);

    boolean existsByBridgeId(String bridgeId);

    boolean existsByCredentialIdentifier(String credentialIdentifier);
}
