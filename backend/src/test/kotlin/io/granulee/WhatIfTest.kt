package io.granulee

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WhatIfTest {
    // As ClickHouse 26.9 prints it for three candidates, one of them estimated from a sample.
    private val text = """
        Baseline (after PK + partition + existing indexes):
          table:       lab.events
          parts:       4
          marks:       367
          est_bytes:   151.27 MiB

        With h_dur (minmax, hypothetical):
          status:       applicable
          marks:        184
          marks_span:   170 to 199
          est_bytes:    75.84 MiB
          skip_ratio:   49.9%

        Estimation:
          source:           empirical
          empirical_status: ok
          sampled_parts:    4 / 4
          sampled_marks:    367 / 755
          elapsed_us:       4286

        With h_type (set, hypothetical):
          status:       not_applicable
          reason:       Index cannot filter this predicate (always unknown or true)

        With h_url (bloom_filter, hypothetical):
          status:       applicable
          marks:        286
          est_bytes:    117.88 MiB
          skip_ratio:   22.1%

        Estimation:
          source:           statistical

        (combined: h_dur, h_url):
          status:       applicable
          marks:        143
          est_bytes:    58.94 MiB
          skip_ratio:   61.0%

        Estimation:
          source:           empirical
          elapsed_us:       0
    """.trimIndent()

    @Test
    fun `the baseline and every candidate are read off the report`() {
        val report = WhatIf.parse(text)
        assertEquals(4, report.parts)
        assertEquals(367, report.granules)
        assertEquals(listOf("h_dur", "h_type", "h_url"), report.estimates.map { it.name })

        val dur = report.estimates[0]
        assertEquals("applicable", dur.status)
        assertEquals(184, dur.granulesAfter)
        assertEquals("empirical", dur.source)
        assertTrue(dur.sampled)

        val type = report.estimates[1]
        assertEquals("not_applicable", type.status)
        assertEquals("Index cannot filter this predicate (always unknown or true)", type.reason)
        assertNull(type.granulesAfter)
        assertNull(type.source)

        val url = report.estimates[2]
        assertEquals(286, url.granulesAfter)
        assertEquals("statistical", url.source)
        assertFalse(url.sampled)

        assertEquals(143, report.combined?.granulesAfter)
        assertEquals("empirical", report.combined?.source)
    }

    @Test
    fun `a report without candidates has only the baseline`() {
        val report = WhatIf.parse(
            """
            Baseline (after PK + partition + existing indexes):
              table:       flight_tracker.movement_data_optimized
              parts:       113
              marks:       7187

            (none):
              status:       not_applicable
              reason:       No hypothetical indexes or projections defined for this table.
            """.trimIndent(),
        )
        assertEquals(7187, report.granules)
        assertTrue(report.estimates.isEmpty())
        assertNull(report.combined)
    }

    @Test
    fun `an index expression gets exactly one pair of parentheses`() {
        assertEquals("(country)", WhatIf.parenthesised("country"))
        assertEquals("(lat, lon)", WhatIf.parenthesised("lat, lon"))
        assertEquals("(lat, lon)", WhatIf.parenthesised("(lat, lon)"))
        assertEquals("((a) + (b))", WhatIf.parenthesised("(a) + (b)"))
        assertEquals("(lower(url))", WhatIf.parenthesised("lower(url)"))
    }
}
