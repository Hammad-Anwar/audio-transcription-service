package com.example.audiotranscription.repository;

import java.util.UUID;

import com.example.audiotranscription.model.AudioFile;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AudioFileRepository extends JpaRepository<AudioFile, UUID> {
}
