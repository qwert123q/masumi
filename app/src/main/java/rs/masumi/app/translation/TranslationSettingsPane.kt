package rs.masumi.app.translation

import android.app.Activity
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import rs.masumi.app.R
import rs.masumi.app.pipeline.PipelineThreading

class TranslationSettingsPane(
    private val activity: Activity,
    private val store: TranslationSettingsStore,
    private val onSettingsSaved: () -> Unit,
    private val modelCatalogClient: TranslationModelCatalogClient = TranslationModelCatalogClient(),
) : AutoCloseable {
    private val providerProfile = activity.findViewById<Spinner>(R.id.translationProviderProfile)
    private val activeProviderStatus = activity.findViewById<TextView>(R.id.activeTranslationProviderStatus)
    private val newProviderButton = activity.findViewById<Button>(R.id.newTranslationProviderButton)
    private val providerName = activity.findViewById<EditText>(R.id.translationProviderName)
    private val endpointPreset = activity.findViewById<Spinner>(R.id.translationEndpointPreset)
    private val apiUrl = activity.findViewById<EditText>(R.id.translationApiUrl)
    private val apiKey = activity.findViewById<EditText>(R.id.translationApiKey)
    private val modelPreset = activity.findViewById<Spinner>(R.id.translationModelPreset)
    private val model = activity.findViewById<EditText>(R.id.translationModel)
    private val toggleButton = activity.findViewById<Button>(R.id.translationSettingsToggleButton)
    private val container = activity.findViewById<View>(R.id.translationSettingsContainer)
    private val fetchModelsButton = activity.findViewById<Button>(R.id.fetchTranslationModelsButton)
    private val fetchProgress = activity.findViewById<ProgressBar>(R.id.translationModelsProgress)
    private val fetchStatus = activity.findViewById<TextView>(R.id.translationModelsStatus)
    private val saveButton = activity.findViewById<Button>(R.id.saveTranslationSettingsButton)
    private val executor = Executors.newSingleThreadExecutor(
        PipelineThreading.factory(MODEL_FETCH_THREAD_NAME),
    )
    private val activeCall = AtomicReference<TranslationModelCatalogCall?>()
    private val endpointOptions = TranslationProviderCatalog.commonEndpoints + TranslationEndpointPreset(
        activity.getString(R.string.translation_custom_endpoint),
        null,
    )
    private var closed = false
    private var fetchGeneration = 0
    private var modelOptions = emptyList<TranslationModelPreset>()
    private var updatingModelOptions = false
    private var providerProfiles = emptyList<SavedTranslationProvider>()
    private var providerOptions = emptyList<TranslationProviderPreset>()
    private var updatingProviderOptions = false
    private var editingProviderId: String? = null

    init {
        endpointPreset.adapter = ArrayAdapter(
            activity,
            android.R.layout.simple_spinner_item,
            endpointOptions,
        ).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

        toggleButton.setOnClickListener { setExpanded(container.visibility != View.VISIBLE) }
        fetchModelsButton.setOnClickListener { fetchModels() }
        newProviderButton.setOnClickListener { prepareNewProvider() }
        saveButton.setOnClickListener { save() }

        endpointPreset.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long,
            ) {
                val selected = endpointOptions[position]
                selected.apiUrl?.let { selectedUrl ->
                    val previousSuggestedName = TranslationProviderCatalog.suggestedName(
                        apiUrl.text.toString(),
                    )
                    apiUrl.setText(selectedUrl)
                    if (
                        providerName.text.toString().isBlank() ||
                        providerName.text.toString() == previousSuggestedName
                    ) {
                        providerName.setText(selected.name)
                    }
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        apiUrl.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(value: CharSequence?, start: Int, count: Int, after: Int) = Unit

            override fun onTextChanged(value: CharSequence?, start: Int, before: Int, count: Int) = Unit

            override fun afterTextChanged(value: Editable?) {
                val position = TranslationProviderCatalog.commonEndpoints
                    .indexOfFirst { it.apiUrl == value?.toString()?.trim() }
                    .takeIf { it >= 0 }
                    ?: endpointOptions.lastIndex
                if (endpointPreset.selectedItemPosition != position) {
                    endpointPreset.setSelection(position, false)
                }
            }
        })
        modelPreset.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long,
            ) {
                if (updatingModelOptions) return
                val selectedModel = modelOptions.getOrNull(position)?.modelId
                if (selectedModel == null) {
                    model.visibility = View.VISIBLE
                    model.requestFocus()
                } else {
                    model.setText(selectedModel)
                    model.visibility = View.GONE
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        model.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(value: CharSequence?, start: Int, count: Int, after: Int) = Unit

            override fun onTextChanged(value: CharSequence?, start: Int, before: Int, count: Int) = Unit

            override fun afterTextChanged(value: Editable?) {
                if (updatingModelOptions || modelPreset.visibility != View.VISIBLE) return
                val position = modelOptions
                    .indexOfFirst { it.modelId == value?.toString()?.trim() }
                    .takeIf { it >= 0 }
                    ?: modelOptions.lastIndex
                if (modelPreset.selectedItemPosition != position) {
                    modelPreset.setSelection(position, false)
                }
            }
        })
        providerProfile.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long,
            ) {
                if (updatingProviderOptions) return
                val providerId = providerOptions.getOrNull(position)?.providerId ?: return
                val selected = providerProfiles.firstOrNull { it.id == providerId } ?: return
                if (
                    selected.id == editingProviderId &&
                    store.loadActiveProvider()?.id == selected.id
                ) {
                    return
                }
                store.selectActiveProvider(selected.id)
                editingProviderId = selected.id
                populateProvider(selected)
                showActiveProvider(selected)
                onSettingsSaved()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        val activeProvider = store.loadActiveProvider()
        if (activeProvider == null) {
            prepareNewProvider()
        } else {
            editingProviderId = activeProvider.id
            bindProviderOptions(activeProvider.id)
            populateProvider(activeProvider)
            showActiveProvider(activeProvider)
        }
        setExpanded(false)
    }

    fun showAndFocus() {
        setExpanded(true)
        toggleButton.post { toggleButton.requestFocus() }
    }

    fun setExpanded(expanded: Boolean) {
        container.visibility = if (expanded) View.VISIBLE else View.GONE
        toggleButton.setText(
            if (expanded) R.string.translation_settings_hide else R.string.translation_settings_show,
        )
    }

    fun setSaveEnabled(enabled: Boolean) {
        saveButton.isEnabled = enabled
        newProviderButton.isEnabled = enabled
        providerProfile.isEnabled = enabled && providerProfiles.isNotEmpty()
    }

    override fun close() {
        closed = true
        fetchGeneration += 1
        activeCall.getAndSet(null)?.cancel()
        executor.shutdownNow()
    }

    private fun fetchModels() {
        val endpoint = apiUrl.text.toString().trim()
        val secret = apiKey.text.toString().trim()
        if (endpoint.isBlank() || secret.isBlank()) {
            fetchStatus.setText(R.string.translation_models_need_endpoint_and_key)
            return
        }
        val call = runCatching {
            modelCatalogClient.newCall(apiUrl = endpoint, apiKey = secret)
        }.getOrElse {
            fetchStatus.setText(R.string.translation_models_invalid_endpoint)
            return
        }
        activeCall.getAndSet(call)?.cancel()
        val generation = ++fetchGeneration
        showFetchInProgress(true)
        executor.execute {
            val result = runCatching { call.execute() }
            activity.runOnUiThread {
                if (closed || generation != fetchGeneration) return@runOnUiThread
                activeCall.compareAndSet(call, null)
                showFetchInProgress(false)
                result.fold(
                    onSuccess = ::showModels,
                    onFailure = ::showFetchFailure,
                )
            }
        }
    }

    private fun showModels(models: List<AvailableTranslationModel>) {
        val ids = models.map(AvailableTranslationModel::id)
        if (ids.isEmpty()) {
            modelPreset.visibility = View.GONE
            fetchStatus.setText(R.string.translation_models_empty)
            return
        }
        val currentModel = model.text.toString().trim()
        modelOptions = ids.map(::TranslationModelPreset) + TranslationModelPreset(
            modelId = null,
            label = activity.getString(R.string.translation_custom_model),
        )
        updatingModelOptions = true
        try {
            modelPreset.adapter = ArrayAdapter(
                activity,
                android.R.layout.simple_spinner_item,
                modelOptions,
            ).also {
                it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            val selected = ids.indexOf(currentModel)
                .takeIf { it >= 0 }
                ?: if (currentModel.isEmpty()) 0 else modelOptions.lastIndex
            modelPreset.setSelection(selected, false)
            modelPreset.visibility = View.VISIBLE
            model.visibility = if (selected == modelOptions.lastIndex) View.VISIBLE else View.GONE
            if (currentModel.isEmpty()) {
                model.setText(ids.first())
            } else {
                model.setText(currentModel)
            }
        } finally {
            updatingModelOptions = false
        }
        fetchStatus.text = activity.resources.getQuantityString(
            R.plurals.translation_models_found,
            ids.size,
            ids.size,
        )
        modelPreset.requestFocus()
        modelPreset.performClick()
    }

    private fun showFetchFailure(failure: Throwable) {
        val code = (failure as? TranslationModelCatalogException)?.code
        fetchStatus.setText(
            when (code) {
                TranslationModelCatalogError.AUTHENTICATION ->
                    R.string.translation_models_authentication_failed
                TranslationModelCatalogError.ENDPOINT_NOT_FOUND ->
                    R.string.translation_models_endpoint_not_found
                TranslationModelCatalogError.TIMEOUT ->
                    R.string.translation_models_timeout
                TranslationModelCatalogError.MALFORMED_RESPONSE,
                TranslationModelCatalogError.RESPONSE_TOO_LARGE,
                -> R.string.translation_models_not_supported
                TranslationModelCatalogError.INVALID_ENDPOINT ->
                    R.string.translation_models_invalid_endpoint
                TranslationModelCatalogError.CANCELLED -> R.string.translation_models_cancelled
                TranslationModelCatalogError.NETWORK,
                TranslationModelCatalogError.HTTP,
                null,
                -> R.string.translation_models_failed
            },
        )
    }

    private fun showFetchInProgress(inProgress: Boolean) {
        fetchModelsButton.isEnabled = !inProgress
        fetchProgress.visibility = if (inProgress) View.VISIBLE else View.GONE
        if (inProgress) fetchStatus.setText(R.string.translation_models_loading)
    }

    private fun prepareNewProvider() {
        resetModelFetch()
        editingProviderId = UUID.randomUUID().toString()
        bindProviderOptions(selectedId = null, includeDraft = true)
        providerName.setText("")
        apiUrl.setText(requireNotNull(TranslationProviderCatalog.commonEndpoints.first().apiUrl))
        endpointPreset.setSelection(0, false)
        apiKey.setText("")
        model.setText("")
        modelPreset.visibility = View.GONE
        modelOptions = emptyList()
        fetchStatus.setText(R.string.translation_models_hint)
        activeProviderStatus.setText(R.string.translation_provider_draft)
        providerName.requestFocus()
    }

    private fun bindProviderOptions(selectedId: String?, includeDraft: Boolean = false) {
        providerProfiles = store.loadProviders()
        providerOptions = providerProfiles.map {
            TranslationProviderPreset(
                providerId = it.id,
                label = "${it.name} · ${it.model}",
            )
        } + if (includeDraft) {
            listOf(
                TranslationProviderPreset(
                    providerId = null,
                    label = activity.getString(R.string.new_translation_provider),
                ),
            )
        } else {
            emptyList()
        }
        updatingProviderOptions = true
        try {
            providerProfile.adapter = ArrayAdapter(
                activity,
                android.R.layout.simple_spinner_item,
                providerOptions,
            ).also {
                it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            val selectedPosition = providerOptions.indexOfFirst { it.providerId == selectedId }
                .takeIf { it >= 0 }
                ?: providerOptions.lastIndex.coerceAtLeast(0)
            if (providerOptions.isNotEmpty()) {
                providerProfile.setSelection(selectedPosition, false)
            }
            providerProfile.isEnabled = providerProfiles.isNotEmpty()
        } finally {
            updatingProviderOptions = false
        }
    }

    private fun populateProvider(provider: SavedTranslationProvider) {
        resetModelFetch()
        providerName.setText(provider.name)
        apiUrl.setText(provider.apiUrl)
        apiKey.setText(provider.apiKey)
        model.setText(provider.model)
        modelPreset.visibility = View.GONE
        modelOptions = emptyList()
        fetchStatus.setText(R.string.translation_models_hint)
        val endpointPosition = TranslationProviderCatalog.commonEndpoints
            .indexOfFirst { preset -> preset.apiUrl == provider.apiUrl }
            .takeIf { it >= 0 }
            ?: endpointOptions.lastIndex
        endpointPreset.setSelection(endpointPosition, false)
    }

    private fun showActiveProvider(provider: SavedTranslationProvider) {
        activeProviderStatus.text = activity.getString(
            R.string.translation_provider_active,
            provider.name,
            provider.model,
        )
    }

    private fun resetModelFetch() {
        activeCall.getAndSet(null)?.cancel()
        fetchGeneration += 1
        showFetchInProgress(false)
    }

    private fun save() {
        val result = runCatching {
            val enteredUrl = apiUrl.text.toString()
            store.saveProvider(
                SavedTranslationProvider(
                    id = requireNotNull(editingProviderId),
                    name = providerName.text.toString().ifBlank {
                        TranslationProviderCatalog.suggestedName(enteredUrl)
                    },
                    apiUrl = enteredUrl,
                    apiKey = apiKey.text.toString(),
                    model = model.text.toString(),
                ),
            )
        }
        if (result.isSuccess) {
            val saved = requireNotNull(store.loadActiveProvider())
            editingProviderId = saved.id
            bindProviderOptions(saved.id)
            populateProvider(saved)
            showActiveProvider(saved)
            Toast.makeText(activity, R.string.translation_settings_saved, Toast.LENGTH_SHORT).show()
            setExpanded(false)
            onSettingsSaved()
        } else {
            Toast.makeText(activity, R.string.translation_settings_invalid, Toast.LENGTH_SHORT).show()
        }
    }

    private data class TranslationModelPreset(
        val modelId: String?,
        val label: String? = null,
    ) {
        override fun toString(): String = modelId ?: requireNotNull(label)
    }

    private data class TranslationProviderPreset(
        val providerId: String?,
        val label: String,
    ) {
        override fun toString(): String = label
    }

    private companion object {
        const val MODEL_FETCH_THREAD_NAME = "masumi-model-catalog"
    }
}
