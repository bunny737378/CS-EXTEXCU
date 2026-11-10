package com.lagradost.cloudstream3.lab

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import android.widget.FrameLayout
import android.widget.ListView
import android.widget.ImageView
import android.widget.BaseAdapter
import android.view.Gravity
import androidx.core.widget.doAfterTextChanged
import coil3.load
import coil3.request.placeholder
import coil3.request.error
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.lab.ManualProviderRunner.Method

class ProviderTesterFragment : Fragment() {
    private val model: ProviderTesterViewModel by viewModels()
    private var providers = emptyList<MainAPI>()
    private lateinit var provider: MaterialButton
    private var selectedProvider: MainAPI? = null
    private lateinit var controls: Triple<Button, Button, TextView>
    private lateinit var method: Spinner
    private lateinit var value: EditText
    private lateinit var page: EditText
    private lateinit var pageName: EditText
    private lateinit var horizontal: CheckBox
    private lateinit var casting: CheckBox
    private lateinit var preset: Button
    private lateinit var status: TextView
    private lateinit var inputHelp: TextView
    private lateinit var nextInput: Button
    private var saved: Bundle? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        saved = savedInstanceState
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        val ctx = requireContext()
        val padding = (16 * resources.displayMetrics.density).toInt()
        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }
        fun text(text: String, size: Float = 15f) = TextView(ctx).apply {
            this.text = text; textSize = size
            setPadding(0, padding / 2, 0, padding / 2)
            content.addView(this)
        }
        fun button(label: String, action: () -> Unit) = MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = label; isAllCaps = false; setOnClickListener { action() }; content.addView(this)
        }
        fun field(hint: String, numeric: Boolean = false) = EditText(ctx).apply {
            this.hint = hint
            inputType = if (numeric) InputType.TYPE_CLASS_NUMBER else
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            minLines = 1; maxLines = 6
            content.addView(this)
        }
        text("Extension Lab", 26f)
        status = text("")
        text("Provider")
        provider = MaterialButton(ctx, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Select provider"
            isAllCaps = false
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            setIconResource(android.R.drawable.ic_menu_search)
            setOnClickListener { showProviderPicker() }
            content.addView(this, LinearLayout.LayoutParams(-1, -2))
        }
        text("Method")
        method = Spinner(ctx).also {
            it.adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, Method.entries.map { m -> m.title })
            content.addView(it)
        }
        preset = button("Use provider main-page preset") {
            val api = selectedProvider ?: return@button
            val entries = api.mainPage
            AlertDialog.Builder(ctx).setTitle("Main page request")
                .setItems(entries.map { it.name.ifBlank { "Default" } }.toTypedArray()) { _, index ->
                    val entry = entries[index]
                    pageName.setText(entry.name); value.setText(entry.data); horizontal.isChecked = entry.horizontalImages
                }.setNegativeButton("Cancel", null).show()
        }
        inputHelp = text("", 13f)
        value = field("Input")
        page = field("Page (positive integer)", true).apply { setText("1") }
        pageName = field("Main-page request name (may be empty)")
        horizontal = CheckBox(ctx).apply { text = "Horizontal images"; content.addView(this) }
        casting = CheckBox(ctx).apply { text = "isCasting"; content.addView(this) }
        method.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { updateFields() }
        }
        val run = button("Run selected API call") {
            val api = selectedProvider
            if (api == null) {
                Toast.makeText(ctx, "Install a provider from Extensions first.", Toast.LENGTH_LONG).show()
                return@button
            }
            val selected = Method.entries[method.selectedItemPosition]
            val needsPage = selected == Method.MAIN_PAGE || selected == Method.PAGED_SEARCH
            val number = if (needsPage) page.text.toString().toIntOrNull() else 1
            if (number == null || number < 1) {
                page.error = "Enter a positive page number"; return@button
            }
            if (selected != Method.MAIN_PAGE && value.text.isBlank()) {
                value.error = "Required input"; return@button
            }
            model.run(api, ManualProviderRunner.Request(selected, value.text.toString(), number,
                pageName.text.toString(), horizontal.isChecked, casting.isChecked))
        }
        val cancel = button("Cancel call") { model.cancel() }
        button("Copy full response") {
            val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            try {
                clipboard.setPrimaryClip(ClipData.newPlainText("Provider response", model.output.value.orEmpty()))
                Toast.makeText(ctx, "Response copied", Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {
                Toast.makeText(ctx, "Response is too large for the clipboard", Toast.LENGTH_LONG).show()
            }
        }
        nextInput = button("Use response as next input") {
            val choices = model.nextInputs.value.orEmpty()
            if (choices.isEmpty()) return@button
            MaterialAlertDialogBuilder(ctx).setTitle("Choose next input")
                .setItems(choices.map { it.label }.toTypedArray()) { _, index ->
                    val choice = choices[index]
                    selectedProvider = model.resultProvider
                    provider.text = selectedProvider?.name ?: "Select provider"
                    method.setSelection(choice.method.ordinal)
                    value.setText(choice.value)
                    (view?.let { it as? FrameLayout }?.getChildAt(0) as? ScrollView)?.smoothScrollTo(0, 0)
                    Toast.makeText(ctx, "Input filled. Press Run to call the provider.", Toast.LENGTH_SHORT).show()
                }.setNegativeButton("Cancel", null).show()
        }.apply { isVisible = false }
        text("Response / error", 20f)
        val response = text("").apply {
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 13f
            setTextIsSelectable(true)
        }
        // Observe after onViewCreated, when viewLifecycleOwner exists.
        controls = Triple(run, cancel, response)
        saved?.let {
            value.setText(it.getString("value", "")); page.setText(it.getString("page", "1"))
            pageName.setText(it.getString("pageName", "")); horizontal.isChecked = it.getBoolean("horizontal")
            casting.isChecked = it.getBoolean("casting"); method.setSelection(it.getInt("method"))
        }
        updateFields()
        val scroll = ScrollView(ctx).apply {
            isFillViewport = true
            clipToPadding = false
            setPadding(0, 0, 0, padding * 5)
            addView(content)
        }
        val top = FloatingActionButton(ctx).apply {
            setImageResource(com.lagradost.cloudstream3.R.drawable.ic_lab_arrow_up)
            contentDescription = "Back to top"
            isVisible = false
            setOnClickListener { scroll.smoothScrollTo(0, 0) }
        }
        scroll.setOnScrollChangeListener { _, _, y, _, _ ->
            top.isVisible = y > padding * 12
        }
        return FrameLayout(ctx).apply {
            addView(scroll, FrameLayout.LayoutParams(-1, -1))
            addView(top, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply {
                setMargins(padding, padding, padding, padding)
            })
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        model.nextInputs.observe(viewLifecycleOwner) { choices ->
            nextInput.isVisible = choices.isNotEmpty()
            nextInput.text = if (choices.firstOrNull()?.method == Method.LOAD) "Use result for load" else "Use data for loadLinks"
        }
        model.output.observe(viewLifecycleOwner) { result ->
            controls.third.text = if (result.length > 200_000) result.take(200_000) +
                "\n\n[Display truncated; Copy full response includes the complete result.]" else result
        }
        model.running.observe(viewLifecycleOwner) { running ->
            controls.first.isEnabled = !running
            controls.second.isEnabled = running
            provider.isEnabled = !running; method.isEnabled = !running
        }
    }

    override fun onResume() {
        super.onResume()
        refreshProviders()
    }

    private fun refreshProviders() {
        val selectedName = selectedProvider?.name ?: saved?.getString("provider")
        providers = APIHolder.apis.toList().sortedWith(compareBy({ it.name }, { it.lang }, { it.mainUrl }))
        selectedProvider = providers.firstOrNull { it === selectedProvider }
            ?: providers.firstOrNull { it.name == selectedName }
        provider.text = selectedProvider?.name ?: "Select provider"
        val host = activity as? ExtensionLabActivity
        status.text = when {
            host?.pluginsLoading == true -> "Loading extensions…"
            host?.pluginLoadError != null -> "Some extensions could not load."
            providers.isEmpty() -> "Install a provider from Extensions to get started."
            else -> ""
        }
        status.isVisible = status.text.isNotBlank()
    }

    /** Refresh on opening: newly installed providers need no extra refresh button. */
    private fun showProviderPicker() {
        refreshProviders()
        val ctx = requireContext()
        val dp = resources.displayMetrics.density
        val padding = (16 * dp).toInt()
        val search = EditText(ctx).apply {
            hint = "Search installed providers"
            inputType = InputType.TYPE_CLASS_TEXT
            maxLines = 1
            setSingleLine(true)
            contentDescription = hint
        }
        val list = ListView(ctx).apply { divider = null }
        val empty = TextView(ctx).apply {
            text = "No providers found"
            gravity = Gravity.CENTER
            setPadding(padding, padding * 2, padding, padding * 2)
        }
        var visibleProviders = providers
        val adapter = object : BaseAdapter() {
            override fun getCount() = visibleProviders.size
            override fun getItem(position: Int) = visibleProviders[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val row = (convertView as? LinearLayout) ?: LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(padding, padding, padding, padding)
                    addView(ImageView(ctx), LinearLayout.LayoutParams((36 * dp).toInt(), (36 * dp).toInt()))
                    addView(TextView(ctx).apply {
                        textSize = 16f
                        setPadding(padding, 0, 0, 0)
                    }, LinearLayout.LayoutParams(-1, -2))
                }
                val api = getItem(position)
                (row.getChildAt(0) as ImageView).apply {
                    contentDescription = null
                    load(api.mainUrl.trimEnd('/') + "/favicon.ico") {
                        placeholder(android.R.drawable.ic_menu_gallery)
                        error(android.R.drawable.ic_menu_gallery)
                    }
                }
                (row.getChildAt(1) as TextView).text = api.name
                return row
            }
        }
        list.adapter = adapter
        val body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, 0, padding, padding)
            addView(search, LinearLayout.LayoutParams(-1, -2))
            addView(empty, LinearLayout.LayoutParams(-1, -2))
            addView(list, LinearLayout.LayoutParams(-1, (320 * dp).toInt()))
        }
        list.emptyView = empty
        val dialog = MaterialAlertDialogBuilder(ctx).setTitle("Select provider")
            .setView(body).setNegativeButton("Cancel", null).create()
        list.setOnItemClickListener { _, _, position, _ ->
            selectedProvider = visibleProviders[position]
            provider.text = selectedProvider?.name
            dialog.dismiss()
        }
        search.doAfterTextChanged {
            val query = it?.toString().orEmpty().trim()
            visibleProviders = providers.filter { api -> api.name.contains(query, ignoreCase = true) }
            adapter.notifyDataSetChanged()
        }
        dialog.show()
    }

    private fun updateFields() {
        val selected = Method.entries[method.selectedItemPosition.coerceAtLeast(0)]
        val home = selected == Method.MAIN_PAGE
        page.isVisible = home || selected == Method.PAGED_SEARCH
        pageName.isVisible = home; horizontal.isVisible = home; preset.isVisible = home
        casting.isVisible = selected == Method.LOAD_LINKS
        inputHelp.text = when (selected) {
            Method.LOAD -> "Paste the search result's url value, not the whole result. JSON inside url is valid. posterHeaders only fetch the poster; load has no headers parameter."
            Method.LOAD_LINKS -> "Paste dataUrl (movie/live) or an episode's data from load. Not the search URL. isCasting is passed separately. Link headers and subtitles are returned in the response."
            Method.MAIN_PAGE -> "Inputs: page + request.name, request.data, request.horizontalImages. Presets fill the provider's own values."
            Method.PAGED_SEARCH -> "Inputs: query + page (starts at 1)."
            Method.SEARCH, Method.QUICK_SEARCH -> "Input: query. This provider method has no headers parameter."
        }
        value.hint = when (selected) {
            Method.MAIN_PAGE -> "MainPageRequest.data (use a preset or enter exact data)"
            Method.LOAD -> "url — exact search result value (URL or JSON string)"
            Method.LOAD_LINKS -> "Exact episode/movie data returned by load (not necessarily a URL)"
            else -> "Search query"
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (!::value.isInitialized) return
        outState.putString("value", value.text.toString()); outState.putString("page", page.text.toString())
        outState.putString("pageName", pageName.text.toString()); outState.putBoolean("horizontal", horizontal.isChecked)
        outState.putBoolean("casting", casting.isChecked); outState.putInt("method", method.selectedItemPosition)
        outState.putString("provider", selectedProvider?.name)
    }
}
