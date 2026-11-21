package io.granulee.trial

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Where trials run, and what is started from them on the production table. Two at a time, so a long merge
 * does not hold everything else up; the rest wait their turn.
 */
class TrialWorkers(private val executor: Executor) : Executor by executor

@Configuration
class TrialWorkersConfiguration {
    @Bean
    fun trialWorkers() = TrialWorkers(Executors.newFixedThreadPool(2) { r -> Thread(r, "granulee-trial").apply { isDaemon = true } })
}
