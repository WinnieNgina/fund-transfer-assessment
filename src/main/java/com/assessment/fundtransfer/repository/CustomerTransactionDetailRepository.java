package com.assessment.fundtransfer.repository;

import com.assessment.fundtransfer.entity.CustomerTransactionDetail;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

public interface CustomerTransactionDetailRepository extends JpaRepository<CustomerTransactionDetail, Long> {

    Optional<CustomerTransactionDetail> findByAccountNumber(String accountNumber);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "0"))
    @Query("""
            select transactionDetail
            from CustomerTransactionDetail transactionDetail
            where transactionDetail.accountNumber = :accountNumber
            """)
    Optional<CustomerTransactionDetail> findByAccountNumberForUpdate(@Param("accountNumber") String accountNumber);

    boolean existsByAccountNumber(String accountNumber);

    boolean existsByTransactionReference(String transactionReference);

    Optional<CustomerTransactionDetail> findByIdempotencyKey(String idempotencyKey);
}
