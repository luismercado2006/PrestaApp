package com.diariopay.repository;

import com.diariopay.model.CajaSession;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CajaSessionRepository extends MongoRepository<CajaSession, String> {
    Optional<CajaSession> findByUserIdAndEstado(String userId, String estado);
    List<CajaSession> findByUserIdOrderByIniciadaEnDesc(String userId);
}