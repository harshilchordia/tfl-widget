package com.tflwidget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Filter
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.work.ExistingWorkPolicy
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One screen to configure a widget: origin, optional destination and display settings. */
class ConfigActivity : AppCompatActivity() {

    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    private lateinit var fromField: StationField
    private lateinit var toField: StationField
    private lateinit var saveButton: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_config)

        widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        // If the user backs out, the widget should not be added.
        setResult(Activity.RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        val hasWidget = widgetId != AppWidgetManager.INVALID_APPWIDGET_ID

        findViewById<View>(R.id.no_widget_banner).visibility = if (hasWidget) View.GONE else View.VISIBLE

        saveButton = findViewById(R.id.save_button)
        fromField = StationField(findViewById(R.id.from_layout), findViewById(R.id.from_input)) { updateSaveEnabled() }
        toField = StationField(findViewById(R.id.to_layout), findViewById(R.id.to_input)) { updateSaveEnabled() }

        val clockSwitch = findViewById<MaterialSwitch>(R.id.show_clock_switch)
        val refreshInput = findViewById<MaterialAutoCompleteTextView>(R.id.refresh_input)

        val refreshLabels = Prefs.REFRESH_OPTIONS.map { if (it == 1) "Every minute" else "Every $it minutes" }
        refreshInput.setSimpleItems(refreshLabels.toTypedArray())
        var refreshIndex = Prefs.REFRESH_OPTIONS.indexOf(Prefs.refreshMinutes(this)).coerceAtLeast(0)
        refreshInput.setText(refreshLabels[refreshIndex], false)
        refreshInput.setOnItemClickListener { _, _, pos, _ -> refreshIndex = pos }

        // Reconfiguring an existing widget: pre-fill its current settings
        if (hasWidget) {
            Prefs.stopId(this, widgetId)?.let { fromField.set(Station(it, Prefs.stopName(this, widgetId) ?: it, emptyList())) }
            Prefs.destId(this, widgetId)?.let { toField.set(Station(it, Prefs.destName(this, widgetId) ?: it, emptyList())) }
            clockSwitch.isChecked = Prefs.showClock(this, widgetId)
        }

        findViewById<View>(R.id.swap_button).setOnClickListener {
            val from = fromField.selected
            val to = toField.selected
            fromField.set(to)
            toField.set(from)
        }

        findViewById<View>(R.id.cancel_button).setOnClickListener { finish() }

        saveButton.setOnClickListener {
            val from = fromField.selected ?: return@setOnClickListener
            val to = toField.selected
            Prefs.setRefreshMinutes(this, Prefs.REFRESH_OPTIONS[refreshIndex])
            Prefs.saveStation(this, widgetId, from.id, from.name)
            Prefs.saveDestination(this, widgetId, to?.id, to?.name)
            Prefs.setShowClock(this, widgetId, clockSwitch.isChecked)
            Prefs.clearCache(this, widgetId) // old station's departures must not be redrawn

            WidgetRenderer.showLoading(this, widgetId)
            DeparturesWidget.refreshNow(this, widgetId)
            DeparturesWidget.schedulePeriodic(this)
            DeparturesWidget.scheduleAuto(this, ExistingWorkPolicy.REPLACE)
            setResult(Activity.RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
            finish()
        }

        updateSaveEnabled()
    }

    private fun updateSaveEnabled() {
        val to = toField.selected
        val sameStation = to != null && to.id == fromField.selected?.id
        toField.layout.error = if (sameStation) getString(R.string.same_station_error) else null
        saveButton.isEnabled = widgetId != AppWidgetManager.INVALID_APPWIDGET_ID &&
            fromField.selected != null && !toField.hasUnresolvedText() && !sameStation
    }

    /**
     * A text field with live TfL station suggestions. Typing searches (debounced);
     * picking a suggestion sets [selected]. Editing the text again clears the selection.
     */
    private inner class StationField(
        val layout: TextInputLayout,
        private val input: MaterialAutoCompleteTextView,
        private val onChange: () -> Unit,
    ) {
        var selected: Station? = null
            private set
        private var searchJob: Job? = null
        private var settingText = false
        private val adapter = StationAdapter()

        init {
            input.setAdapter(adapter)
            input.threshold = 2
            input.setOnItemClickListener { _, _, pos, _ ->
                set(adapter.getItem(pos))
                input.clearFocus()
            }
            input.doAfterTextChanged { text ->
                if (settingText) return@doAfterTextChanged
                selected = null
                layout.helperText = null
                onChange()
                search(text?.toString().orEmpty().trim())
            }
        }

        fun set(station: Station?) {
            settingText = true
            selected = station
            input.setText(station?.let { WidgetRenderer.shortName(it.name) } ?: "", false)
            layout.helperText = station?.modes?.takeIf { it.isNotEmpty() }?.let { modesLabel(it) }
            settingText = false
            onChange()
        }

        /** Text typed but no suggestion picked: we don't know which station they mean. */
        fun hasUnresolvedText() = selected == null && input.text.isNotBlank()

        private fun search(query: String) {
            searchJob?.cancel()
            if (query.length < 2) {
                adapter.update(emptyList())
                return
            }
            searchJob = lifecycleScope.launch {
                delay(300) // wait for the user to stop typing
                layout.helperText = getString(R.string.searching)
                val result = withContext(Dispatchers.IO) {
                    runCatching { TflApi.searchStations(query) }
                }
                result.onSuccess { stations ->
                    layout.helperText = if (stations.isEmpty()) getString(R.string.no_results) else null
                    adapter.update(stations)
                    // Run the field's own filter so it shows/hides the suggestion popup itself
                    if (input.hasFocus()) adapter.filter.filter(input.text, input)
                }.onFailure {
                    android.util.Log.w("TflConfig", "search '$query' failed", it)
                    layout.helperText = getString(R.string.search_failed, it.message ?: "network error")
                }
            }
        }
    }

    /** Suggestion list showing station name + modes. Results come from TfL, so no local filtering. */
    private inner class StationAdapter : ArrayAdapter<Station>(this, R.layout.item_station) {
        private var items: List<Station> = emptyList()

        fun update(stations: List<Station>) {
            items = stations
            notifyDataSetChanged()
        }

        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: LayoutInflater.from(context).inflate(R.layout.item_station, parent, false)
            val s = items[position]
            v.findViewById<TextView>(R.id.station_name).text = WidgetRenderer.shortName(s.name)
            v.findViewById<TextView>(R.id.station_modes).text = modesLabel(s.modes)
            return v
        }

        override fun getFilter() = object : Filter() {
            override fun performFiltering(constraint: CharSequence?) =
                FilterResults().apply { values = items; count = items.size }
            override fun publishResults(constraint: CharSequence?, results: FilterResults?) = notifyDataSetChanged()
            override fun convertResultToString(resultValue: Any?) =
                (resultValue as? Station)?.let { WidgetRenderer.shortName(it.name) } ?: ""
        }
    }

    private fun modesLabel(modes: List<String>) = modes.joinToString(" · ") {
        when (it) {
            "tube" -> "Underground"
            "dlr" -> "DLR"
            "overground" -> "Overground"
            "elizabeth-line" -> "Elizabeth line"
            "national-rail" -> "National Rail"
            "bus" -> "Bus"
            "tram" -> "Tram"
            else -> it.replaceFirstChar { c -> c.uppercase() }
        }
    }
}
