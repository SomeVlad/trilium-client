package ru.vlad.triliumnotes

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.vlad.triliumnotes.data.NotesServices
import ru.vlad.triliumnotes.data.TriliumAuth
import ru.vlad.triliumnotes.data.authFailureMessage
import ru.vlad.triliumnotes.data.TriliumSession
import ru.vlad.triliumnotes.data.LegacyImportException

data class ConnectionScreenState(
    val restoring: Boolean = true,
    val busy: Boolean = false,
    val services: NotesServices? = null,
    val error: String? = null,
    val storageFailed: Boolean = false,
    val legacySession: TriliumSession? = null,
    val legacyPendingCount: Int? = null,
)

class ConnectionViewModel(application: Application) : AndroidViewModel(application) {
    private val mutable = MutableStateFlow(ConnectionScreenState())
    val state = mutable.asStateFlow()
    private val notesStore = ViewModelStore()
    private val notesOwner = object : ViewModelStoreOwner { override val viewModelStore = notesStore }
    private var notesServices: NotesServices? = null
    private var notes: NotesViewModel? = null
    private var lastFailure: Throwable? = null
    private var failedLegacyImport = false

    private fun failureMessage(error: Throwable, legacyImport: Boolean = false): String {
        lastFailure = error
        failedLegacyImport = legacyImport
        return if (legacyImport && error !is LegacyImportException)
            AppLanguage.get(getApplication(), R.string.msg_legacy_failed)
        else authFailureMessage(getApplication(), error)
    }

    /** Keep the diagnosis and connection state when only its display language changes. */
    fun refreshLanguage() {
        val error = lastFailure ?: return
        if (mutable.value.error != null)
            mutable.value = mutable.value.copy(error = failureMessage(error, failedLegacyImport))
    }

    fun notesModel(services: NotesServices): NotesViewModel {
        if (notesServices !== services) {
            notesStore.clear()
            notesServices = services
            notes = ViewModelProvider(notesOwner, object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    NotesViewModel(getApplication(), services) as T
            })[NotesViewModel::class.java]
        }
        return requireNotNull(notes)
    }

    override fun onCleared() { notesStore.clear() }

    init {
        viewModelScope.launch {
            try {
                val services = withContext(Dispatchers.IO) { NotesServices.current(application) }
                mutable.value = ConnectionScreenState(restoring = false, services = services)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                mutable.value = ConnectionScreenState(restoring = false,
                    error = failureMessage(error), storageFailed = true)
            }
        }
    }

    fun connect(serverUrl: String, secret: String, useToken: Boolean, allowHttp: Boolean) {
        if (mutable.value.busy || mutable.value.restoring || mutable.value.storageFailed || mutable.value.legacySession != null) return
        mutable.value = mutable.value.copy(busy = true, error = null)
        viewModelScope.launch {
            try {
                val session = TriliumAuth().authenticate(serverUrl, secret, useToken, allowHttp)
                ensureActive()
                val legacy = withContext(Dispatchers.IO) { NotesServices.legacyImportAvailable(getApplication()) }
                if (legacy) {
                    val count = withContext(Dispatchers.IO) { NotesServices.legacyPendingCount(getApplication()) }
                    mutable.value = mutable.value.copy(busy = false, legacySession = session, legacyPendingCount = count)
                    return@launch
                }
                val services = withContext(Dispatchers.IO) {
                    NotesServices.activate(getApplication(), session)
                }
                mutable.value = ConnectionScreenState(restoring = false, services = services)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                mutable.value = mutable.value.copy(busy = false, error = failureMessage(error))
            }
        }
    }

    fun finishLegacyImport(import: Boolean) {
        val session = mutable.value.legacySession ?: return
        if (mutable.value.busy) return
        mutable.value = mutable.value.copy(busy = true, error = null)
        viewModelScope.launch {
            try {
                val services = withContext(Dispatchers.IO) {
                    if (import) NotesServices.importLegacy(getApplication(), session)
                    else NotesServices.activate(getApplication(), session)
                }
                mutable.value = ConnectionScreenState(restoring = false, services = services)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                mutable.value = mutable.value.copy(busy = false, error = failureMessage(error, import))
            }
        }
    }

    fun disconnect() {
        if (mutable.value.busy) return
        mutable.value = mutable.value.copy(busy = true, error = null)
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { NotesServices.signOut(getApplication()) }
                notesStore.clear(); notes = null; notesServices = null
                mutable.value = ConnectionScreenState(restoring = false)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                mutable.value = mutable.value.copy(busy = false, error = failureMessage(error))
            }
        }
    }
}
