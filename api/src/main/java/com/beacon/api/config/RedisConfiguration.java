package com.beacon.api.config;

import com.beacon.api.hazards.HazardAreaCacheEntry;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.JdkSerializationRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Configuration
public class RedisConfiguration {

    @Bean
    public RedisTemplate<String, List<HazardAreaCacheEntry>> hazardAreaRedisTemplate(
            RedisConnectionFactory connectionFactory
    ) {
        RedisTemplate<String, List<HazardAreaCacheEntry>> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new JdkSerializationRedisSerializer());
        template.afterPropertiesSet();
        return template;
    }
}
