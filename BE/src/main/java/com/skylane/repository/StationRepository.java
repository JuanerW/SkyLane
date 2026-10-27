package com.skylane.repository;

import com.skylane.entity.Station;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StationRepository extends JpaRepository<Station, UUID> {
    Optional<Station> findByCode(String code);
    List<Station> findByActiveTrueOrderByCodeAsc();
}
