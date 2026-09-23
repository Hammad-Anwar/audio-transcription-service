package com.example.audiotranscription.dto;

import java.time.Instant;
import java.util.UUID;

public record ApiError(
        int status,
        String error,
        String message,
        UUID audioId,
        Instant timestamp
) {
}
