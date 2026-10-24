package com.tflwidget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.tflwidget.Prefs.appKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ConfigActivity : AppCompatActivity() {

    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private var results: List<Station> = emptyList()
    private var origin: Station? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_config)

        widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        // If the user cancels, the widget should not be added.
        setResult(Activity.RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))

        val query = findViewById<EditText>(R.id.search_input)
        val keyInput = findViewById<EditText>(R.id.app_key_input)
        val list = findViewById<ListView>(R.id.results)
        val status = findViewById<TextView>(R.id.status)

        keyInput.setText(appKey ?: "")
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            status.text = getString(R.string.add_widget_hint)
        }

        fun search() {
            val q = query.text.toString().trim()
            if (q.length < 2) return
            appKey = keyInput.text.toString().trim().ifBlank { null }
            status.text = getString(R.string.searching)
            status.visibility = View.VISIBLE
            lifecycleScope.launch {
                val r = withContext(Dispatchers.IO) { runCatching { TflApi.searchStations(q, appKey) } }
                r.onSuccess { stations ->
                    results = stations
                    status.visibility = if (stations.isEmpty()) View.VISIBLE else View.GONE
                    status.text = getString(R.string.no_results)
                    list.adapter = object : ArrayAdapter<Station>(
                        this@ConfigActivity, android.R.layout.simple_list_item_2, android.R.id.text1, stations
                    ) {
                        override fun getView(pos: Int, convertView: View?, parent: android.view.ViewGroup): View {
                            val v = super.getView(pos, convertView, parent)
                            val s = getItem(pos)!!
                            v.findViewById<TextView>(android.R.id.text1).text = s.name
                            v.findViewById<TextView>(android.R.id.text2).text = s.modes.joinToString(", ")
                            return v
                        }
                    }
                }.onFailure {
                    status.text = getString(R.string.search_failed, it.message)
                }
            }
        }

        findViewById<Button>(R.id.search_button).setOnClickListener { search() }
        query.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEARCH) { search(); true } else false
        }

        val stepTitle = findViewById<TextView>(R.id.step_title)
        val skip = findViewById<Button>(R.id.skip_button)
        val clockSwitch = findViewById<CompoundButton>(R.id.show_clock_switch)
        if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            clockSwitch.isChecked = Prefs.showClock(this, widgetId)
        }

        fun finishWith(dest: Station?) {
            val o = origin ?: return
            Prefs.saveStation(this, widgetId, o.id, o.name)
            Prefs.saveDestination(this, widgetId, dest?.id, dest?.name)
            Prefs.setShowClock(this, widgetId, clockSwitch.isChecked)
            WidgetRenderer.showLoading(this, widgetId)
            DeparturesWidget.refreshNow(this, widgetId)
            DeparturesWidget.schedulePeriodic(this)
            setResult(Activity.RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
            finish()
        }

        skip.setOnClickListener { finishWith(null) }

        list.setOnItemClickListener { _, _, pos, _ ->
            val station = results[pos]
            appKey = keyInput.text.toString().trim().ifBlank { null }
            if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
                Toast.makeText(this, R.string.add_widget_hint, Toast.LENGTH_LONG).show()
                return@setOnItemClickListener
            }
            if (origin == null) {
                // Step 2: optional destination
                origin = station
                results = emptyList()
                list.adapter = null
                query.setText("")
                query.hint = getString(R.string.dest_hint)
                stepTitle.text = getString(R.string.choose_destination, station.name)
                skip.visibility = View.VISIBLE
                status.visibility = View.GONE
                query.requestFocus()
            } else {
                finishWith(station)
            }
        }
    }
}
