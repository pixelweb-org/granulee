package io.granulee.explorer

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
@RequestMapping("/api")
class ExplorerController(private val explorer: ExplorerService, private val recommendations: RecommendationService) {

    @GetMapping("/overview")
    fun overview(@RequestParam(required = false) source: String?): Overview = explorer.overview(source)

    @GetMapping("/opportunities")
    fun opportunities(@RequestParam(required = false) source: String?): List<Opportunity> = explorer.opportunities(source)

    @GetMapping("/sources/{id}/databases")
    fun databases(@PathVariable id: String): DatabaseList = explorer.databases(id)

    @GetMapping("/sources/{id}/databases/{db}")
    fun database(@PathVariable id: String, @PathVariable db: String): DatabaseDetail = explorer.database(id, db)

    @GetMapping("/sources/{id}/databases/{db}/tables/{table}")
    fun table(@PathVariable id: String, @PathVariable db: String, @PathVariable table: String): TableDetail = explorer.table(id, db, table)

    @GetMapping("/sources/{id}/databases/{db}/tables/{table}/recommendations")
    fun recommendations(@PathVariable id: String, @PathVariable db: String, @PathVariable table: String): TableRecommendations =
        recommendations.of(id, db, table)

    @PostMapping("/sources/{id}/databases/{db}/tables/{table}/recommendations/dismiss")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun dismiss(@PathVariable id: String, @PathVariable db: String, @PathVariable table: String, @RequestBody body: DismissRequest) =
        recommendations.dismiss(id, db, table, body.id)

    @DeleteMapping("/sources/{id}/databases/{db}/tables/{table}/recommendations/dismissed")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun restore(@PathVariable id: String, @PathVariable db: String, @PathVariable table: String) = recommendations.restore(id, db, table)
}
