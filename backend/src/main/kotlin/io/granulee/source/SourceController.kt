package io.granulee.source

import io.granulee.scan.SnapshotStore
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

@RestController
@RequestMapping("/api")
class SourceController(
    private val sources: SourceService,
    private val scans: SourceScanService,
    private val snapshots: SnapshotStore,
) {
    private fun dto(source: SourceRecord): SourceDto = source.toDto(snapshots.of(source.id), Instant.now())

    @GetMapping("/sources")
    fun list(): List<SourceDto> = sources.all().map(::dto)

    @PostMapping("/sources")
    fun create(@RequestBody input: SourceInput): SourceDto {
        val created = sources.create(input)
        scans.scanInBackground(created.id)
        return dto(created)
    }

    @PutMapping("/sources/{id}")
    fun update(@PathVariable id: String, @RequestBody input: SourceInput): SourceDto {
        sources.update(id, input)
        return dto(sources.checkHealth(id) ?: sources.find(id))
    }

    @DeleteMapping("/sources/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable id: String) {
        sources.delete(id)
        scans.forget(id)
    }

    @PostMapping("/sources/test")
    fun test(@RequestBody input: SourceInput): ConnectionTest = sources.test(input)

    @PostMapping("/sources/{id}/scan")
    fun scan(@PathVariable id: String): SourceDto {
        sources.find(id)
        return dto(scans.scan(id))
    }

    @PostMapping("/scan")
    fun scanAll(): List<SourceDto> = sources.all().map { dto(scans.scan(it.id)) }
}
