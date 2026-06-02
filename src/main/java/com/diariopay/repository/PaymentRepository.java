package com.diariopay.repository;

import com.diariopay.model.Payment;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;
import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface PaymentRepository extends MongoRepository<Payment, String> {
    List<Payment> findByUserId(String userId);
    List<Payment> findByLoanId(String loanId);
    List<Payment> findByUserIdAndDateBetween(String userId, LocalDateTime from, LocalDateTime to);
}
