package core.conf

import com.hazelcast.core.Hazelcast
import com.hazelcast.core.HazelcastInstance
import com.hazelcast.spring.cache.HazelcastCacheManager
import org.springframework.cache.CacheManager
import org.springframework.cache.annotation.EnableCaching
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration


@Configuration
@EnableCaching
class CachingConfig {

    @Bean
    fun hzInstance(): HazelcastInstance? {
        return Hazelcast.newHazelcastInstance()
    }

    @Bean
    fun cacheManager(hzInstance: HazelcastInstance?): CacheManager? {
        return HazelcastCacheManager(hzInstance)
    }
}