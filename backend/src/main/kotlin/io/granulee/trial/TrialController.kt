package io.granulee.trial

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/trials")
class TrialController(private val trials: TrialService, private val production: ProductionChanges) {
    @GetMapping
    fun list(@RequestParam(required = false) source: String?): TrialList = trials.list(source)

    @GetMapping("/plan")
    fun plan(@RequestParam source: String, @RequestParam database: String, @RequestParam table: String): TrialPlan =
        trials.plan(source, database, table)

    @GetMapping("/{id}")
    fun get(@PathVariable id: String): Trial = trials.find(id)

    @PostMapping
    fun start(@RequestBody request: TrialRequest): Trial = trials.start(request)

    @GetMapping("/{id}/apply")
    fun applyPlan(@PathVariable id: String): ApplyPlan = production.plan(id)

    @PostMapping("/{id}/apply")
    fun apply(@PathVariable id: String, @RequestBody request: ApplyRequest): Trial = production.apply(id, request.sql)

    @PostMapping("/{id}/optimize")
    fun optimize(@PathVariable id: String): Trial = production.optimize(id)

    @PostMapping("/{id}/cancel")
    fun cancel(@PathVariable id: String): Trial = trials.cancel(id)

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable id: String) = trials.delete(id)
}
