package com.assessment.fundtransfer.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "transfer_transactions")
public class TransferTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String transferReference;

    @Column(nullable = false, unique = true)
    private String idempotencyKey;

    @Column(nullable = false)
    private String sourceAccountNumber;

    @Column(nullable = false)
    private String destinationAccountNumber;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal sourceBalanceAfterTransfer;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal destinationBalanceAfterTransfer;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TransactionStatus status;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        createdAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getTransferReference() {
        return transferReference;
    }

    public void setTransferReference(String transferReference) {
        this.transferReference = transferReference;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public String getSourceAccountNumber() {
        return sourceAccountNumber;
    }

    public void setSourceAccountNumber(String sourceAccountNumber) {
        this.sourceAccountNumber = sourceAccountNumber;
    }

    public String getDestinationAccountNumber() {
        return destinationAccountNumber;
    }

    public void setDestinationAccountNumber(String destinationAccountNumber) {
        this.destinationAccountNumber = destinationAccountNumber;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public BigDecimal getSourceBalanceAfterTransfer() {
        return sourceBalanceAfterTransfer;
    }

    public void setSourceBalanceAfterTransfer(BigDecimal sourceBalanceAfterTransfer) {
        this.sourceBalanceAfterTransfer = sourceBalanceAfterTransfer;
    }

    public BigDecimal getDestinationBalanceAfterTransfer() {
        return destinationBalanceAfterTransfer;
    }

    public void setDestinationBalanceAfterTransfer(BigDecimal destinationBalanceAfterTransfer) {
        this.destinationBalanceAfterTransfer = destinationBalanceAfterTransfer;
    }

    public TransactionStatus getStatus() {
        return status;
    }

    public void setStatus(TransactionStatus status) {
        this.status = status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
