package io.granulee

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration
import org.springframework.scheduling.annotation.EnableScheduling

// Granulee has its own accounts; Spring Boot's stand-in user with a generated password is not wanted.
@SpringBootApplication(exclude = [UserDetailsServiceAutoConfiguration::class])
@ConfigurationPropertiesScan
@EnableScheduling
class GranuleeApplication

fun main(args: Array<String>) {
    runApplication<GranuleeApplication>(*args)
}
