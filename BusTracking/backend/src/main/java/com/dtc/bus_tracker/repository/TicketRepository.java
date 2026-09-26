package com.dtc.bus_tracker.repository;

import com.dtc.bus_tracker.entity.Ticket;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TicketRepository extends JpaRepository<Ticket, Long> {

    // Every seat-availability calculation needs a trip's tickets together
    // with their seat/passenger/stops, so fetch them in one query.
    @EntityGraph(attributePaths = {"seat", "passenger", "fromStop", "toStop"})
    List<Ticket> findByTrip_IdOrderByIdAsc(Long tripId);

    @EntityGraph(attributePaths = {"seat", "passenger", "fromStop", "toStop", "bus", "trip"})
    Optional<Ticket> findByTicketNumber(String ticketNumber);

    Optional<Ticket> findByRequestId(String requestId);
}
