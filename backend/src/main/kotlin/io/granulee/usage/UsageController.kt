package io.granulee.usage

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api")
class UsageController(private val usage: UsageService) {
    @GetMapping("/sources/{id}/databases/{db}/tables/{table}/usage")
    fun usage(@PathVariable id: String, @PathVariable db: String, @PathVariable table: String): TableUsage = usage.usage(id, db, table)
}
