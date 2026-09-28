package com.rakshika.app.ui.screens.demo

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rakshika.app.risk.AccuracyStats
import com.rakshika.app.risk.EquationVersion
import com.rakshika.app.risk.RiskLoop
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * What the Analysis tab shows: the equation this phone would score the next route with right now,
 * every earlier version it evolved from, and how well it has matched riders so far. Pulled straight
 * from [RiskLoop]'s on-device store — nothing here is scripted for the demo.
 */
data class AnalysisState(
    val loading: Boolean = true,
    val current: EquationVersion? = null,
    /** Earlier versions, most recent first, excluding [current]. */
    val history: List<EquationVersion> = emptyList(),
    val accuracy: AccuracyStats = AccuracyStats(ratedTrips = 0, meanAbsError = null, lastError = null)
)

class AnalysisViewModel(app: Application) : AndroidViewModel(app) {
    private val riskLoop = RiskLoop(app)

    private val _state = MutableStateFlow(AnalysisState())
    val state: StateFlow<AnalysisState> = _state

    init { refresh() }

    /** Re-reads the store — call when the tab is opened so a trip rated since the last visit shows up. */
    fun refresh() {
        viewModelScope.launch {
            val current = riskLoop.currentEquationVersion()
            val history = riskLoop.equationHistory()
                .filter { it.version != current.version }
                .asReversed()
            _state.value = AnalysisState(
                loading = false,
                current = current,
                history = history,
                accuracy = riskLoop.accuracy()
            )
        }
    }
}
