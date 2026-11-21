package io.granulee.index

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/sources/{id}/databases/{db}/tables/{table}")
class IndexController(private val usage: IndexUsageService, private val whatIf: WhatIfService) {

    @GetMapping("/index-usage")
    fun usage(@PathVariable id: String, @PathVariable db: String, @PathVariable table: String): TableIndexUsage = usage.usage(id, db, table)

    @PostMapping("/index-whatif")
    fun estimate(@PathVariable id: String, @PathVariable db: String, @PathVariable table: String, @RequestBody request: WhatIfRequest): TableWhatIf =
        whatIf.estimate(id, db, table, request.candidates)
}
