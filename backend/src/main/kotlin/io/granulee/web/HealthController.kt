package io.granulee.web

import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.info.BuildProperties
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/** For container health checks, and to see which release is running. */
@RestController
class HealthController(build: ObjectProvider<BuildProperties>) {
    private val version = build.ifAvailable?.version ?: "dev"

    @GetMapping("/api/health")
    fun health(): Map<String, String> = mapOf("status" to "UP", "version" to version)
}
