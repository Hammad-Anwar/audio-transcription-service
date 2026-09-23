package com.example.audiotranscription.config;

import java.time.Duration;

import com.example.audiotranscription.dto.AudioMetadataDto;
import com.example.audiotranscription.dto.TranscriptDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.cache.autoconfigure.RedisCacheManagerBuilderCustomizer;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.cache.Cache;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.serializer.JacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Configuration(proxyBeanMethods = false)
@EnableCaching
public class CacheConfiguration implements CachingConfigurer {

    public static final String AUDIO_METADATA_CACHE = "audioMetadata";
    public static final String TRANSCRIPTS_CACHE = "transcripts";

    private static final Logger logger = LoggerFactory.getLogger(CacheConfiguration.class);

    @Bean
    RedisCacheManagerBuilderCustomizer redisCacheManagerBuilderCustomizer(
            @Value("${spring.cache.redis.time-to-live:30m}") String ttlValue
    ) {
        Duration ttl = DurationStyle.detectAndParse(ttlValue);
        return builder -> builder
                .withCacheConfiguration(
                        AUDIO_METADATA_CACHE,
                        typedConfiguration(ttl, AudioMetadataDto.class)
                )
                .withCacheConfiguration(
                        TRANSCRIPTS_CACHE,
                        typedConfiguration(ttl, TranscriptDto.class)
                );
    }

    private static <T> RedisCacheConfiguration typedConfiguration(
            Duration ttl,
            Class<T> valueType
    ) {
        return RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(ttl)
                .disableCachingNullValues()
                .serializeKeysWith(SerializationPair.fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(SerializationPair.fromSerializer(
                        new JacksonJsonRedisSerializer<>(valueType)
                ));
    }

    @Override
    public CacheErrorHandler errorHandler() {
        return new CacheErrorHandler() {
            @Override
            public void handleCacheGetError(RuntimeException exception, Cache cache, Object key) {
                log("read", exception, cache, key);
            }

            @Override
            public void handleCachePutError(
                    RuntimeException exception,
                    Cache cache,
                    Object key,
                    Object value
            ) {
                log("write", exception, cache, key);
            }

            @Override
            public void handleCacheEvictError(RuntimeException exception, Cache cache, Object key) {
                log("evict", exception, cache, key);
            }

            @Override
            public void handleCacheClearError(RuntimeException exception, Cache cache) {
                log("clear", exception, cache, null);
            }
        };
    }

    private static void log(String operation, RuntimeException exception, Cache cache, Object key) {
        logger.warn(
                "Cache {} failed cache={} key={} cause={}; continuing without Redis",
                operation,
                cache.getName(),
                key,
                exception.getMessage()
        );
    }
}
