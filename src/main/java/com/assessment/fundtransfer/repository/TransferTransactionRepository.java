package com.assessment.fundtransfer.repository;

import com.assessment.fundtransfer.entity.TransferTransaction;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TransferTransactionRepository extends JpaRepository<TransferTransaction, Long> {

    boolean existsByTransferReference(String transferReference);

    Optional<TransferTransaction> findByIdempotencyKey(String idempotencyKey);
}
