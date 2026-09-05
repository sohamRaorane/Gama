    package com.meshpay.auth.entity;

    import jakarta.persistence.Column;
    import jakarta.persistence.Entity;
    import jakarta.persistence.GeneratedValue;
    import jakarta.persistence.GenerationType;
    import jakarta.persistence.Id;
    import jakarta.persistence.Table;
    import java.time.Instant;
    import java.util.UUID;
    import lombok.AllArgsConstructor;
    import lombok.Builder;
    import lombok.Getter;
    import lombok.NoArgsConstructor;
    import lombok.Setter;
    import org.hibernate.annotations.CreationTimestamp;
    import org.hibernate.annotations.UpdateTimestamp;

    @Entity
    @Table(name = "bridges")
    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public class Bridge {

        @Id
        @GeneratedValue(strategy = GenerationType.UUID)
        @Column(name = "id", nullable = false, updatable = false)
        private UUID id;

        @Column(name = "bridge_id", nullable = false, unique = true, length = 100)
        private String bridgeId;

        @Column(name = "credential_identifier", nullable = false, unique = true, length = 150)
        private String credentialIdentifier;

        @Column(name = "credential_hash", nullable = false, length = 255)
        private String credentialHash;

        @Builder.Default
        @Column(name = "enabled", nullable = false)
        private boolean enabled = true;

        @CreationTimestamp
        @Column(name = "created_at", nullable = false, updatable = false)
        private Instant createdAt;

        @UpdateTimestamp
        @Column(name = "updated_at", nullable = false)
        private Instant updatedAt;
    }
