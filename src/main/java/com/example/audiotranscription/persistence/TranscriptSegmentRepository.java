package com.example.audiotranscription.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TranscriptSegmentRepository extends JpaRepository<TranscriptSegment, Long> {

    List<TranscriptSegment> findByAudioFileIdOrderBySegmentIndexAsc(UUID audioId);

    @Query("""
            select segment from TranscriptSegment segment
            where segment.audioFile.id = :audioId
              and segment.startTimeMs <= :timeMs
              and segment.endTimeMs > :timeMs
            order by segment.segmentIndex
            """)
    List<TranscriptSegment> findAtTimeCandidates(
            @Param("audioId") UUID audioId,
            @Param("timeMs") long timeMs,
            Pageable pageable
    );

    default Optional<TranscriptSegment> findAtTime(UUID audioId, long timeMs) {
        return findAtTimeCandidates(audioId, timeMs, PageRequest.of(0, 1))
                .stream()
                .findFirst();
    }
}
