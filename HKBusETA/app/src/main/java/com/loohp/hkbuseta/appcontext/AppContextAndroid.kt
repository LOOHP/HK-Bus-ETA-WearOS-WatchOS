/*
 * This file is part of HKBusETA.
 *
 * Copyright (C) 2023. LoohpJames <jamesloohp@gmail.com>
 * Copyright (C) 2023. Contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.loohp.hkbuseta.appcontext

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Stable
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.core.util.AtomicFile
import com.google.firebase.Firebase
import com.google.firebase.analytics.analytics
import com.loohp.hkbuseta.DismissibleTextDisplayActivity
import com.loohp.hkbuseta.EtaActivity
import com.loohp.hkbuseta.EtaMenuActivity
import com.loohp.hkbuseta.FatalErrorActivity
import com.loohp.hkbuseta.FavActivity
import com.loohp.hkbuseta.FavRouteListViewActivity
import com.loohp.hkbuseta.ListRoutesActivity
import com.loohp.hkbuseta.ListStopsActivity
import com.loohp.hkbuseta.MainActivity
import com.loohp.hkbuseta.NearbyActivity
import com.loohp.hkbuseta.SearchActivity
import com.loohp.hkbuseta.TitleActivity
import com.loohp.hkbuseta.URLImageActivity
import com.loohp.hkbuseta.services.AlightReminderService
import com.loohp.hkbuseta.shared.Shared
import com.loohp.hkbuseta.tiles.EtaTileConfigureActivity
import com.loohp.hkbuseta.utils.BackgroundRestrictionType
import com.loohp.hkbuseta.utils.RemoteActivityUtils
import com.loohp.hkbuseta.utils.currentBackgroundRestricted
import com.loohp.hkbuseta.utils.getConnectionType
import com.loohp.hkbuseta.utils.startActivity
import io.ktor.utils.io.charsets.Charset
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import kotlin.math.abs
import kotlin.streams.asSequence


@Stable
open class AppContextAndroid internal constructor(val context: Context) : AppContext {

    override val packageName: String
        get() = context.packageName

    override val versionName: String
        get() = context.packageManager.getPackageInfo(context.packageName, 0).versionName

    override val versionCode: Long
        get() = context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode

    override val screenWidth: Int
        get() = abs(context.resources.displayMetrics.widthPixels)

    override val screenHeight: Int
        get() = abs(context.resources.displayMetrics.heightPixels)

    override val minScreenSize: Int
        get() = screenWidth.coerceAtMost(screenHeight)

    override val screenScale: Float
        get() = minScreenSize / 454f

    override val density: Float
        get() = context.resources.displayMetrics.density

    override val scaledDensity: Float
        get() = context.resources.displayMetrics.scaledDensity

    override fun readTextFile(fileName: String, charset: Charset): String {
        BufferedReader(InputStreamReader(context.applicationContext.openFileInput(fileName), charset)).use { reader ->
            return reader.lines().asSequence().joinToString()
        }
    }

    override fun readTextFileSequence(fileName: String, charset: Charset): List<String> {
        BufferedReader(InputStreamReader(context.applicationContext.openFileInput(fileName), charset)).use { reader ->
            return reader.lines().asSequence().toList()
        }
    }

    override fun writeTextFileSequence(fileName: String, charset: Charset, writeText: () -> List<String>) {
        AtomicFile(context.applicationContext.getFileStreamPath(fileName)).let { file ->
            file.startWrite().use { fos ->
                PrintWriter(OutputStreamWriter(fos, charset)).use { pw ->
                    writeText.invoke().forEach { pw.write(it) }
                    pw.flush()
                    file.finishWrite(fos)
                }
            }
        }
    }

    override fun listFiles(): List<String> {
        return context.applicationContext.fileList().asList()
    }

    override fun deleteFile(fileName: String): Boolean {
        return context.applicationContext.deleteFile(fileName)
    }

    override fun hasConnection(): Boolean {
        return context.getConnectionType().hasConnection()
    }

    override fun currentBackgroundRestrictions(): BackgroundRestrictionType {
        return context.currentBackgroundRestricted()
    }

    override fun logFirebaseEvent(title: String, values: AppBundle) {
        Firebase.analytics.logEvent(title, values.toAndroidBundle())
    }

    override fun getResourceString(resId: Int): String {
        return context.resources.getString(resId)
    }

    override fun startActivity(appIntent: AppIntent) {
        val intent = Intent((appIntent.context as AppContextAndroid).context, appIntent.screen.activityClass)
        appIntent.intentFlags.toAndroidFlags()?.let { intent.addFlags(it) }
        appIntent.extras.toAndroidBundle()?.let { intent.putExtras(it) }
        context.startActivity(intent)
    }

    override fun startForegroundService(appIntent: AppIntent) {
        val intent = Intent((appIntent.context as AppContextAndroid).context, appIntent.screen.activityClass)
        appIntent.intentFlags.toAndroidFlags()?.let { intent.addFlags(it) }
        appIntent.extras.toAndroidBundle()?.let { intent.putExtras(it) }
        context.startForegroundService(intent)
    }

    override fun showToastText(text: String, duration: ToastDuration) {
        Toast.makeText(context, text, duration.androidValue).show()
    }

}

@Stable
class AppActiveContextAndroid internal constructor(val activity: ComponentActivity) : AppContextAndroid(activity), AppActiveContext {

    override val screenWidth: Int
        get() = abs(activity.windowManager.currentWindowMetrics.bounds.width())

    override val screenHeight: Int
        get() = abs(activity.windowManager.currentWindowMetrics.bounds.height())

    override fun runOnUiThread(runnable: () -> Unit) {
        activity.runOnUiThread(runnable)
    }

    override fun startActivity(appIntent: AppIntent, callback: (AppIntentResult) -> Unit) {
        val intent = Intent((appIntent.context as AppContextAndroid).context, appIntent.screen.activityClass)
        appIntent.intentFlags.toAndroidFlags()?.let { intent.addFlags(it) }
        appIntent.extras.toAndroidBundle()?.let { intent.putExtras(it) }
        activity.startActivity(intent) { callback.invoke(AppIntentResult(it.resultCode)) }
    }

    override fun handleOpenMaps(lat: Double, lng: Double, label: String, longClick: Boolean, haptics: HapticFeedback): () -> Unit {
        return {
            val intent = Intent(Intent.ACTION_VIEW)
                .addCategory(Intent.CATEGORY_BROWSABLE)
                .setData(Uri.parse("geo:0,0?q=".plus(lat).plus(",").plus(lng).plus("(").plus(label).plus(")")))
            if (longClick) {
                activity.startActivity(intent)
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            } else {
                RemoteActivityUtils.intentToPhone(
                    instance = activity,
                    intent = intent,
                    noPhone = {
                        try {
                            activity.startActivity(intent)
                        } catch (e: Throwable) {
                            e.printStackTrace()
                        }
                    },
                    failed = {
                        try {
                            activity.startActivity(intent)
                        } catch (e: Throwable) {
                            e.printStackTrace()
                        }
                    },
                    success = {
                        runOnUiThread {
                            showToastText(if (Shared.language == "en") "Please check your phone" else "請在手機上繼續", ToastDuration.SHORT)
                        }
                    }
                )
            }
        }
    }

    override fun handleWebpages(url: String, longClick: Boolean, haptics: HapticFeedback): () -> Unit {
        return {
            val intent = Intent(Intent.ACTION_VIEW)
                .addCategory(Intent.CATEGORY_BROWSABLE)
                .setData(Uri.parse(url))
            RemoteActivityUtils.intentToPhone(
                instance = activity,
                intent = intent,
                noPhone = {
                    try {
                        activity.startActivity(intent)
                    } catch (e: Throwable) {
                        e.printStackTrace()
                    }
                },
                failed = {
                    try {
                        activity.startActivity(intent)
                    } catch (e: Throwable) {
                        e.printStackTrace()
                    }
                },
                success = {
                    runOnUiThread {
                        showToastText(if (Shared.language == "en") "Please check your phone" else "請在手機上繼續", ToastDuration.SHORT)
                    }
                }
            )
        }
    }

    override fun handleWebImages(url: String, longClick: Boolean, haptics: HapticFeedback): () -> Unit {
        return {
            val phoneIntent = Intent(activity, AppScreen.URL_IMAGE.activityClass)
            phoneIntent.putExtra("url", url)
            if (longClick) {
                activity.startActivity(phoneIntent)
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            } else {
                val intent = Intent(Intent.ACTION_VIEW)
                    .addCategory(Intent.CATEGORY_BROWSABLE)
                    .setData(Uri.parse(url))
                RemoteActivityUtils.intentToPhone(
                    instance = activity,
                    intent = intent,
                    noPhone = {
                        activity.startActivity(phoneIntent)
                    },
                    failed = {
                        activity.startActivity(phoneIntent)
                    },
                    success = {
                        runOnUiThread {
                            showToastText(if (Shared.language == "en") "Please check your phone" else "請在手機上繼續", ToastDuration.SHORT)
                        }
                    }
                )
            }
        }
    }

    override fun setResult(result: AppIntentResult) {
        activity.setResult(result.resultCode)
    }

    override fun finish() {
        activity.finish()
    }

    override fun finishAffinity() {
        activity.finishAffinity()
    }

}

val ComponentActivity.appContext: AppActiveContext get() = AppActiveContextAndroid(this)

val Context.appContext: AppContext get() = AppContextAndroid(this)

val AppScreen.activityClass: Class<*> get() = when (this) {
    AppScreen.DISMISSIBLE_TEXT_DISPLAY -> DismissibleTextDisplayActivity::class.java
    AppScreen.FATAL_ERROR -> FatalErrorActivity::class.java
    AppScreen.ETA -> EtaActivity::class.java
    AppScreen.ETA_MENU -> EtaMenuActivity::class.java
    AppScreen.FAV -> FavActivity::class.java
    AppScreen.FAV_ROUTE_LIST_VIEW -> FavRouteListViewActivity::class.java
    AppScreen.LIST_ROUTES -> ListRoutesActivity::class.java
    AppScreen.LIST_STOPS -> ListStopsActivity::class.java
    AppScreen.MAIN -> MainActivity::class.java
    AppScreen.NEARBY -> NearbyActivity::class.java
    AppScreen.SEARCH -> SearchActivity::class.java
    AppScreen.TITLE -> TitleActivity::class.java
    AppScreen.URL_IMAGE -> URLImageActivity::class.java
    AppScreen.ALIGHT_REMINDER_SERVICE -> AlightReminderService::class.java
    AppScreen.ETA_TILE_CONFIGURE -> EtaTileConfigureActivity::class.java
}

val AppIntentFlag.androidFlag: Int @SuppressLint("WearRecents") get() = when (this) {
    AppIntentFlag.NEW_TASK -> Intent.FLAG_ACTIVITY_NEW_TASK
    AppIntentFlag.CLEAR_TASK -> Intent.FLAG_ACTIVITY_CLEAR_TASK
    AppIntentFlag.NO_ANIMATION -> Intent.FLAG_ACTIVITY_NO_ANIMATION
}

fun Collection<AppIntentFlag>.toAndroidFlags(): Int? {
    return asSequence().map { it.androidFlag }.reduceOrNull { a, b -> a or b}
}

fun AppBundle.toAndroidBundle(): Bundle? {
    if (data.isEmpty()) {
        return null
    }
    return Bundle().apply {
        for ((key, value) in data) {
            when (value) {
                is Boolean -> putBoolean(key, value)
                is Byte -> putByte(key, value)
                is Char -> putChar(key, value)
                is Short -> putShort(key, value)
                is Int -> putInt(key, value)
                is Long -> putLong(key, value)
                is Float -> putFloat(key, value)
                is Double -> putDouble(key, value)
                is String -> putString(key, value)
                is CharSequence -> putCharSequence(key, value)
                is BooleanArray -> putBooleanArray(key, value)
                is ByteArray -> putByteArray(key, value)
                is ShortArray -> putShortArray(key, value)
                is CharArray -> putCharArray(key, value)
                is IntArray -> putIntArray(key, value)
                is LongArray -> putLongArray(key, value)
                is FloatArray -> putFloatArray(key, value)
                is DoubleArray -> putDoubleArray(key, value)
                is ArrayList<*> -> @Suppress("UNCHECKED_CAST") putStringArrayList(key, value as ArrayList<String>)
                is AppBundle -> putAppBundle(key, value)
            }
        }
    }
}

val ToastDuration.androidValue: Int get() = when (this) {
    ToastDuration.SHORT -> Toast.LENGTH_SHORT
    ToastDuration.LONG -> Toast.LENGTH_LONG
}