package com.diariopay.repository;

import com.diariopay.model.PaymentProof;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface PaymentProofRepository extends MongoRepository<PaymentProof, String> {
    List<PaymentProof> findByUserIdOrderByFechaDesc(String userId);
    List<PaymentProof> findByUserIdAndLeidoFalseOrderByFechaDesc(String userId);
    long countByUserIdAndLeidoFalse(String userId);
    List<PaymentProof> findByLoanIdOrderByFechaDesc(String loanId);
}