package com.diariopay.repository;

import com.diariopay.model.Loan;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface LoanRepository extends MongoRepository<Loan, String> {
    List<Loan> findByUserId(String userId);
    List<Loan> findByStatus(String status);
    List<Loan> findByUserIdAndStatus(String userId, String status);
    List<Loan> findByUserIdOrderByCreatedAtDesc(String userId);
    long countByUserIdAndStatus(String userId, String status);
}
