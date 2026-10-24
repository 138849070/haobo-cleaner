package com.haobo.cleaner

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

sealed interface ShellState {
    data object NotInstalled : ShellState
    data object NotRunning : ShellState
    data object NoPermission : ShellState
    data object Ready : ShellState
}

data class ShellResult(
    val exitCode: Int,
    val stdout: String,
    val error: String? = null,
) {
    val ok: Boolean get() = exitCode == 0
}

class ShellClient(private val context: Context) {

    private val _state = MutableStateFlow<ShellState>(ShellState.NotRunning)
    val state: StateFlow<ShellState> = _state.asStateFlow()

    @Volatile
    private var remote: IShellService? = null

    private var listenersAttached = false

    private val permissionListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == PERMISSION_REQUEST_CODE) {
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    bindService()
                } else {
                    _state.value = ShellState.NoPermission
                }
            }
        }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        refresh()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        remote = null
        _state.value = ShellState.NotRunning
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            remote = IShellService.Stub.asInterface(service)
            _state.value = ShellState.Ready
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            remote = null
            _state.value = ShellState.NotRunning
        }
    }

    fun attach() {
        if (listenersAttached) return
        listenersAttached = true
        runCatching {
            Shizuku.addRequestPermissionResultListener(permissionListener)
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
        }.onFailure { Log.w(TAG, "failed to register Shizuku listeners", it) }
        refresh()
    }

    fun detach() {
        if (!listenersAttached) return
        listenersAttached = false
        runCatching {
            Shizuku.removeRequestPermissionResultListener(permissionListener)
            Shizuku.removeBinderReceivedListener(binderReceivedListener)
            Shizuku.removeBinderDeadListener(binderDeadListener)
            Shizuku.unbindUserService(userServiceArgs(), connection, true)
        }.onFailure { Log.w(TAG, "failed to release Shizuku", it) }
    }

    fun refresh() {
        if (!isShizukuInstalled()) {
            _state.value = ShellState.NotInstalled
            return
        }
        val alive = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        if (!alive) {
            _state.value = ShellState.NotRunning
            return
        }
        val granted = runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        if (!granted) {
            _state.value = ShellState.NoPermission
            return
        }
        bindService()
    }

    fun requestPermission() {
        if (!isShizukuInstalled()) {
            _state.value = ShellState.NotInstalled
            return
        }
        if (!runCatching { Shizuku.pingBinder() }.getOrDefault(false)) {
            _state.value = ShellState.NotRunning
            return
        }
        val granted = runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        if (granted) {
            bindService()
        } else {
            runCatching { Shizuku.requestPermission(PERMISSION_REQUEST_CODE) }
                .onFailure { Log.w(TAG, "requestPermission failed", it) }
        }
    }

    private fun bindService() {
        runCatching {
            Shizuku.bindUserService(userServiceArgs(), connection)
        }.onFailure {
            _state.value = ShellState.NotRunning
        }
    }

    private fun userServiceArgs(): Shizuku.UserServiceArgs =
        Shizuku.UserServiceArgs(
            ComponentName(context.packageName, ShellService::class.java.name)
        )
            .daemon(false)
            .processNameSuffix("shell")
            .debuggable(false)
            .version(1)

    private fun isShizukuInstalled(): Boolean = runCatching {
        context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
        true
    }.getOrDefault(false)

    suspend fun exec(command: String): ShellResult = withContext(Dispatchers.IO) {
        val service = remote
            ?: return@withContext ShellResult(-1, "", "Shizuku 服务未绑定")
        try {
            val raw = service.exec(command)
            val split = raw.indexOf('\n')
            if (split < 0) return@withContext ShellResult(-1, raw, null)
            val code = raw.substring(0, split).removePrefix("__EXIT__").toIntOrNull() ?: -1
            ShellResult(code, raw.substring(split + 1))
        } catch (t: Throwable) {
            ShellResult(-1, "", t.message)
        }
    }

    suspend fun scanFiles(
        root: String,
        include: List<String>,
        exclude: List<String>,
        excludeDirs: List<String>,
        minSize: Long,
        maxDepth: Int,
        maxResults: Int,
    ): Array<String> = withContext(Dispatchers.IO) {
        val service = remote ?: return@withContext emptyArray()
        try {
            service.scanFiles(
                root,
                include.toTypedArray(),
                exclude.toTypedArray(),
                excludeDirs.toTypedArray(),
                minSize,
                maxDepth,
                maxResults,
            ) ?: emptyArray()
        } catch (t: Throwable) {
            emptyArray()
        }
    }

    suspend fun sizeOf(path: String): Long = withContext(Dispatchers.IO) {
        val service = remote ?: return@withContext 0L
        try {
            service.sizeOf(path)
        } catch (t: Throwable) {
            0L
        }
    }

    suspend fun deletePath(path: String): Boolean = withContext(Dispatchers.IO) {
        val service = remote ?: return@withContext false
        try {
            service.deletePath(path)
        } catch (t: Throwable) {
            false
        }
    }

    suspend fun freeBytes(): Long = withContext(Dispatchers.IO) {
        val service = remote ?: return@withContext 0L
        try {
            service.freeBytes()
        } catch (t: Throwable) {
            0L
        }
    }

    private companion object {
        const val TAG = "HaoboCleaner"
        const val PERMISSION_REQUEST_CODE = 4001
        const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    }
}
