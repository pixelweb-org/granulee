package io.granulee.trial

import kotlin.test.Test
import kotlin.test.assertTrue

class TrialQueryResultTest {
    @Test
    fun `two medians only tell the tables apart when their runs do not overlap`() {
        fun result(base: List<Double>?, cand: List<Double>?) =
            TrialQueryResult("Q1", "q", 0.1, 9.1, 4.0, 1, 1, null, baseRuns = base, candRuns = cand)
        // The runs from the trial that raised the question: 2.3× by the medians, and no run of one in the range of the other.
        assertTrue(result(listOf(6.1, 7.8, 9.9, 12.7), listOf(5.5, 3.9, 3.9, 3.8)).clearDifference())
        assertTrue(!result(listOf(6.1, 7.8, 9.9, 12.7), listOf(6.5, 3.9, 3.9, 3.8)).clearDifference())
        assertTrue(!result(listOf(4.0, 4.1), listOf(4.1, 9.0)).clearDifference())
        // A trial from before the runs were kept has only the medians to go by.
        assertTrue(result(null, null).clearDifference())
    }
}
