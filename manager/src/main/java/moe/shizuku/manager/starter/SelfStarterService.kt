package moe.shizuku.manager.starter

import android.Manifest
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.ConnectivityManager.NetworkCallback
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import moe.shizuku.manager.AppConstants.TAG
import moe.shizuku.manager.BuildConfig
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.adb.AdbClient
import moe.shizuku.manager.adb.AdbKey
import moe.shizuku.manager.adb.AdbKeyException
import moe.shizuku.manager.adb.AdbMdns
import moe.shizuku.manager.adb.PreferenceAdbKeyStore
import moe.shizuku.manager.utils.EnvironmentUtils
import rikka.lifecycle.Resource
import rikka.shizuku.Shizuku


class SelfStarterService : Service(), LifecycleOwner {

    private var accessibility_service = ""
    private val sb = StringBuilder()
    private lateinit var adbMdns: AdbMdns
    private val port = MutableLiveData<Int>()
    private val lifecycleOwner = LifecycleRegistry(this)
    private val _output = MutableLiveData<Resource<StringBuilder>>()
    val output = _output as LiveData<Resource<StringBuilder>>

    val mNetworkCallback = object : NetworkCallback() {
        override fun onAvailable(network: Network) {
            super.onAvailable(network)
            if (ContextCompat.checkSelfPermission(this@SelfStarterService, Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this@SelfStarterService, "WiFi Connected: Starting Wireless ADB", Toast.LENGTH_SHORT).show()
                Settings.Global.putInt(
                    contentResolver,
                    "adb_wifi_enabled",
                    0
                )
                accessibility_service = Settings.Secure.getString(
                    contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                )
                Settings.Secure.putString(
                    contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    "moe.shizuku.privileged.api/moe.shizuku.manager.adb.AdbAccessibilityService:${accessibility_service}"
                )
                Settings.Secure.putString(
                    contentResolver,
                    Settings.Secure.ACCESSIBILITY_ENABLED,
                    "1"
                )
                adbPortListener();
                Settings.Global.putInt(
                    contentResolver,
                    "adb_wifi_enabled",
                    1
                )
            } else {
                Toast.makeText(this@SelfStarterService, "Write Secure Settings Permission not Granted - Abort AutoStart", Toast.LENGTH_SHORT).show()
                stopSelf()
            }
        }
    }

    private val observer = Observer<Int> { dport ->
        if (dport == null || dport > 65535 || dport < 1)
            return@Observer
        if (Shizuku.pingBinder()) {
            stopSelf()
        } else {
            try {
                startAdb(dport)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val mConnectivityManager =
            getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val mNetworkRequest = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        mConnectivityManager.registerNetworkCallback(mNetworkRequest, mNetworkCallback);
        return START_STICKY
    }

    private fun adbPortListener() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            adbMdns = AdbMdns(this, AdbMdns.TLS_CONNECT, port)
            val handler: Handler = Handler(Looper.getMainLooper()) //This is the main thread
            handler.post(Runnable
            {
                port.observeForever(observer)
            })
            adbMdns.start()
            Toast.makeText(this, "Listening for ADB Port...", Toast.LENGTH_SHORT).show()
        } else {
            val port = EnvironmentUtils.getAdbTcpPort()
            if (port > 0) {
                startAdb(port)
            }
        }
    }

    private fun postResult(throwable: Throwable? = null) {
        if (throwable == null)
            _output.postValue(Resource.success(sb))
        else
            _output.postValue(Resource.error(throwable, sb))
    }

    private fun startAdb(port: Int) {
        Starter.writeSdcardFiles(applicationContext)
        Toast.makeText(this, "Found ADB, Starting Shizuku Server...", Toast.LENGTH_SHORT).show()
        sb.append("Starting with wireless adb...").append('\n').append('\n')
        postResult()

        GlobalScope.launch(Dispatchers.IO) {
            val key = try {
                AdbKey(PreferenceAdbKeyStore(ShizukuSettings.getPreferences()), "shizuku")
            } catch (e: Throwable) {
                e.printStackTrace()
                sb.append('\n').append(Log.getStackTraceString(e))
                postResult(AdbKeyException(e))
                return@launch
            }
            AdbClient("127.0.0.1", port, key).runCatching {
                connect()
                shellCommand(Starter.sdcardCommand) {
                    sb.append(String(it))
                    postResult()
                }
                close()
            }.onFailure {
                it.printStackTrace()
                sb.append('\n').append(Log.getStackTraceString(it))
                postResult(it)
            }
            /* Adb on MIUI Android 11 has no permission to access Android/data.
               Before MIUI Android 12, we can temporarily use /data/user_de.
               After that, is better to implement "adb push" and push files directly to /data/local/tmp.
             */
            if (sb.contains("/Android/data/${BuildConfig.APPLICATION_ID}/start.sh: Permission denied")) {
                sb.append('\n')
                    .appendLine("adb have no permission to access Android/data, how could this possible ?!")
                    .appendLine("try /data/user_de instead...")
                    .appendLine()
                postResult()
                Starter.writeDataFiles(application, true)

                AdbClient("127.0.0.1", port, key).runCatching {
                    connect()
                    shellCommand(Starter.dataCommand) {
                        sb.append(String(it))
                        postResult()
                    }
                    close()
                }.onFailure {
                    it.printStackTrace()
                    sb.append('\n').append(Log.getStackTraceString(it))
                    postResult(it)
                }
            }
            stopSelf()
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun getLifecycle(): Lifecycle {
        return lifecycleOwner
    }

    override fun onDestroy() {
        super.onDestroy()
        Toast.makeText(this, "AutoStart Process Complete", Toast.LENGTH_SHORT).show()
        val mConnectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        if (mNetworkCallback != null) {
            mConnectivityManager.unregisterNetworkCallback(mNetworkCallback)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED) {
            if (accessibility_service != null) {
                Settings.Secure.putString(
                    this.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    accessibility_service
                )
            }
            Settings.Global.putInt(contentResolver, "adb_wifi_enabled", 0)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            adbMdns.stop()
            port.removeObserver(observer)
        }
    }
}
