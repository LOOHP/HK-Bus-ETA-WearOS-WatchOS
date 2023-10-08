package com.loohp.hkbuseta

import android.content.Intent
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.AbsoluteSizeSpan
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.animateColor
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.Text
import com.loohp.hkbuseta.compose.fullPageVerticalLazyScrollbar
import com.loohp.hkbuseta.shared.Registry
import com.loohp.hkbuseta.shared.Registry.StopData
import com.loohp.hkbuseta.shared.Shared
import com.loohp.hkbuseta.theme.HKBusETATheme
import com.loohp.hkbuseta.utils.DistanceUtils
import com.loohp.hkbuseta.utils.LocationUtils
import com.loohp.hkbuseta.utils.ScreenSizeUtils
import com.loohp.hkbuseta.utils.StringUtils
import com.loohp.hkbuseta.utils.UnitUtils
import com.loohp.hkbuseta.utils.adjustBrightness
import com.loohp.hkbuseta.utils.clamp
import com.loohp.hkbuseta.utils.clampSp
import com.loohp.hkbuseta.utils.dp
import com.loohp.hkbuseta.utils.formatDecimalSeparator
import com.loohp.hkbuseta.utils.toAnnotatedString
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt


data class StopEntry(
    val stopIndex: Int,
    val stopName: String,
    val stopData: StopData,
    val lat: Double,
    val lng: Double,
    var distance: Double = Double.MAX_VALUE
)

data class OriginData(
    val lat: Double,
    val lng: Double,
    val onlyInRange: Boolean = false
)


class ListStopsActivity : ComponentActivity() {

    private val executor: ScheduledExecutorService = Executors.newScheduledThreadPool(Runtime.getRuntime().availableProcessors() * 4)
    private val etaUpdatesMap: MutableMap<Int, Pair<ScheduledFuture<*>?, () -> Unit>> = LinkedHashMap()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Shared.setDefaultExceptionHandler(this)
        val route = intent.extras!!.getString("route")?.let { JSONObject(it) }?: throw RuntimeException()

        setContent {
            MainElement(this, route) { isAdd, index, task ->
                synchronized(etaUpdatesMap) {
                    if (isAdd) {
                        etaUpdatesMap.computeIfAbsent(index) { executor.scheduleWithFixedDelay(task, 0, 30, TimeUnit.SECONDS) to task!! }
                    } else {
                        etaUpdatesMap.remove(index)?.first?.cancel(true)
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        Shared.setSelfAsCurrentActivity(this)
    }

    override fun onResume() {
        super.onResume()
        synchronized(etaUpdatesMap) {
            etaUpdatesMap.replaceAll { _, value ->
                value.first?.cancel(true)
                executor.scheduleWithFixedDelay(value.second, 0, 30, TimeUnit.SECONDS) to value.second
            }
        }
    }

    override fun onPause() {
        super.onPause()
        synchronized(etaUpdatesMap) {
            etaUpdatesMap.forEach { it.value.first?.cancel(true) }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) {
            executor.shutdownNow()
            Shared.removeSelfFromCurrentActivity(this)
        }
    }

}


@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MainElement(instance: ListStopsActivity, route: JSONObject, schedule: (Boolean, Int, (() -> Unit)?) -> Unit) {
    HKBusETATheme {
        val focusRequester = remember { FocusRequester() }
        val scroll = rememberLazyListState()
        val scope = rememberCoroutineScope()
        val haptic = LocalHapticFeedback.current
        var scrollCounter by remember { mutableStateOf(0) }
        val scrollInProgress by remember { derivedStateOf { scroll.isScrollInProgress } }
        val scrollReachedEnd by remember { derivedStateOf { scroll.canScrollBackward != scroll.canScrollForward } }
        var scrollMoved by remember { mutableStateOf(0) }

        val kmbCtbJoint = remember { route.optJSONObject("route")!!.optBoolean("kmbCtbJoint", false) }
        val routeNumber = remember { route.optJSONObject("route")!!.optString("route") }
        val co = remember { route.optString("co") }
        val bound = remember { if (co.equals("nlb")) route.optJSONObject("route")!!.optString("nlbId") else route.optJSONObject("route")!!.optJSONObject("bound")!!.optString(co) }
        val gtfsId = remember { route.optJSONObject("route")!!.optString("gtfsId") }
        val interchangeSearch = remember { route.optBoolean("interchangeSearch", false) }

        val stopsList = remember { Registry.getInstance(instance).getAllStops(routeNumber, bound, co, gtfsId) }
        val lowestServiceType = remember { stopsList.minOf { it.serviceType } }

        val distances: MutableMap<Int, Double> = remember { mutableMapOf() }
        var closestIndex by remember { mutableStateOf(0) }

        val etaTextWidth by remember { derivedStateOf { StringUtils.findTextLengthDp(instance, "99", StringUtils.scaledSize(16F, instance)) + 1F } }

        val etaUpdateTimes: MutableMap<Int, Long> = remember { mutableMapOf() }
        val etaResults: MutableMap<Int, Registry.ETAQueryResult> = remember { mutableMapOf() }

        val mutex by remember { mutableStateOf(Mutex()) }
        val animatedScrollValue = remember { Animatable(0F) }
        var previousScrollValue by remember { mutableStateOf(0F) }
        LaunchedEffect (animatedScrollValue.value) {
            val diff = previousScrollValue - animatedScrollValue.value
            scroll.scrollBy(diff)
            previousScrollValue -= diff
        }

        LaunchedEffect (scrollInProgress) {
            if (scrollInProgress) {
                scrollCounter++
            }
        }
        LaunchedEffect (scrollCounter, scrollReachedEnd) {
            delay(50)
            if (scrollReachedEnd && scrollMoved > 1) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            }
            if (scrollMoved <= 1) {
                scrollMoved++
            }
        }
        LaunchedEffect (Unit) {
            focusRequester.requestFocus()

            val scrollTask: (OriginData) -> Unit = { origin ->
                stopsList.withIndex().map {
                    val (index, entry) = it
                    val stop = entry.stop
                    val location = stop.optJSONObject("location")!!
                    var stopStr = stop.optJSONObject("name")!!.optString(Shared.language)
                    if (Shared.language == "en") {
                        stopStr = StringUtils.capitalize(stopStr)
                    }
                    StopEntry(index + 1, stopStr, entry, location.optDouble("lat"), location.optDouble("lng"))
                }.onEach {
                    it.distance = DistanceUtils.findDistance(origin.lat, origin.lng, it.lat, it.lng)
                    distances[it.stopIndex] = it.distance
                }.minBy {
                    it.distance
                }.let {
                    if (!origin.onlyInRange || it.distance <= 0.3) {
                        closestIndex = it.stopIndex
                        scope.launch {
                            scroll.animateScrollToItem(it.stopIndex + 1, (-ScreenSizeUtils.getScreenHeight(instance) / 2) - UnitUtils.spToPixels(instance, StringUtils.scaledSize(15F, instance)).roundToInt())
                        }
                    }
                }
            }

            if (route.has("origin")) {
                val origin = route.optJSONObject("origin")!!
                scrollTask.invoke(OriginData(origin.optDouble("lat"), origin.optDouble("lng")))
            } else {
                LocationUtils.checkLocationPermission(instance) {
                    if (it) {
                        val future = LocationUtils.getGPSLocation(instance)
                        Thread {
                            try {
                                val locationResult = future.get()
                                if (locationResult.isSuccess) {
                                    val location = locationResult.location
                                    scrollTask.invoke(OriginData(location.latitude, location.longitude, true))
                                }
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }.start()
                    }
                }
            }
        }

        LazyColumn (
            modifier = Modifier
                .fillMaxSize()
                .fullPageVerticalLazyScrollbar(
                    state = scroll
                )
                .onRotaryScrollEvent {
                    scope.launch {
                        mutex.withLock {
                            val target = it.verticalScrollPixels + animatedScrollValue.value
                            animatedScrollValue.snapTo(target)
                            previousScrollValue = target
                        }
                        animatedScrollValue.animateTo(
                            0F,
                            TweenSpec(durationMillis = 300, easing = FastOutSlowInEasing)
                        )
                    }
                    true
                }
                .focusRequester(
                    focusRequester = focusRequester
                )
                .focusable(),
            horizontalAlignment = Alignment.CenterHorizontally,
            state = scroll
        ) {
            item {
                Spacer(modifier = Modifier.size(35.dp))
            }
            val padding = StringUtils.scaledSize(7.5F, instance)
            for ((index, entry) in stopsList.withIndex()) {
                item {
                    val i = index + 1
                    val isClosest = closestIndex == i
                    val stopId = entry.stopId
                    val stop = entry.stop
                    val brightness = if (entry.serviceType == lowestServiceType) 1F else 0.65F
                    val rawColor = (if (isClosest) when (co) {
                        "kmb" -> if (Shared.isLWBRoute(routeNumber)) Color(0xFFF26C33) else Color(0xFFFF4747)
                        "ctb" -> Color(0xFFFFE15E)
                        "nlb" -> Color(0xFF9BFFC6)
                        "mtr-bus" -> Color(0xFFAAD4FF)
                        "gmb" -> Color(0xFF36FF42)
                        "lightRail" -> Color(0xFFD3A809)
                        "mtr" -> {
                            when (route.optJSONObject("route")!!.optString("route")) {
                                "AEL" -> Color(0xFF00888E)
                                "TCL" -> Color(0xFFF3982D)
                                "TML" -> Color(0xFF9C2E00)
                                "TKL" -> Color(0xFF7E3C93)
                                "EAL" -> Color(0xFF5EB7E8)
                                "SIL" -> Color(0xFFCBD300)
                                "TWL" -> Color(0xFFE60012)
                                "ISL" -> Color(0xFF0075C2)
                                "KTL" -> Color(0xFF00A040)
                                "DRL" -> Color(0xFFEB6EA5)
                                else -> Color.White
                            }
                        }
                        else -> Color.White
                    } else Color.White).adjustBrightness(brightness)
                    var stopStr = stop.optJSONObject("name")!!.optString(Shared.language)
                    if (Shared.language == "en") {
                        stopStr = StringUtils.capitalize(stopStr)
                    }

                    Box (
                        modifier = Modifier
                            .fillParentMaxWidth()
                            .combinedClickable(
                                onClick = {
                                    val intent = Intent(instance, EtaActivity::class.java)
                                    intent.putExtra("stopId", stopId)
                                    intent.putExtra("co", co)
                                    intent.putExtra("index", i)
                                    intent.putExtra("stop", stop.toString())
                                    intent.putExtra("route", route.optJSONObject("route")!!.toString())
                                    instance.startActivity(intent)
                                },
                                onLongClick = {
                                    instance.runOnUiThread {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        instance.runOnUiThread {
                                            val text = if (isClosest) {
                                                "".plus(i).plus(". ").plus(stopStr).plus("\n")
                                                    .plus(if (interchangeSearch) (if (Shared.language == "en") "Interchange " else "轉乘") else (if (Shared.language == "en") "Nearby " else "附近"))
                                                    .plus(((distances[i]?: Double.NaN) * 1000).roundToInt().formatDecimalSeparator()).plus(if (Shared.language == "en") "m" else "米")
                                            } else {
                                                "".plus(i).plus(". ").plus(stopStr)
                                            }
                                            Toast.makeText(instance, text, Toast.LENGTH_LONG).show()
                                        }
                                    }
                                }
                            )
                    ) {
                        Row (
                            modifier = Modifier
                                .padding(25.dp, 0.dp)
                                .fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.Bottom
                        ) {
                            val color = if (isClosest && kmbCtbJoint) {
                                val infiniteTransition = rememberInfiniteTransition(label = "JointColor")
                                val animatedColor by infiniteTransition.animateColor(
                                    initialValue = rawColor,
                                    targetValue = Color(0xFFFFE15E).adjustBrightness(brightness),
                                    animationSpec = infiniteRepeatable(
                                        animation = tween(5000, easing = LinearEasing),
                                        repeatMode = RepeatMode.Reverse,
                                        initialStartOffset = StartOffset(1500)
                                    ),
                                    label = "JointColor"
                                )
                                animatedColor
                            } else {
                                rawColor
                            }

                            var eta: Registry.ETAQueryResult? by remember { mutableStateOf(etaResults[index]) }

                            LaunchedEffect (Unit) {
                                if (eta != null && !eta!!.isConnectionError) {
                                    delay(etaUpdateTimes[index]?.let { (30000 - (System.currentTimeMillis() - it)).coerceAtLeast(0) }?: 0)
                                }
                                schedule.invoke(true, index) {
                                    eta = Registry.getEta(stopId, route.optString("co"), route.optJSONObject("route")!!, instance)
                                    etaUpdateTimes[index] = System.currentTimeMillis()
                                    etaResults[index] = eta!!
                                }
                            }
                            DisposableEffect (Unit) {
                                onDispose {
                                    schedule.invoke(false, index, null)
                                }
                            }

                            Text(
                                modifier = Modifier
                                    .padding(0.dp, padding.dp)
                                    .requiredWidth(30.dp),
                                textAlign = TextAlign.Start,
                                fontSize = TextUnit(StringUtils.scaledSize(15F, instance), TextUnitType.Sp),
                                fontWeight = if (isClosest) FontWeight.Bold else FontWeight.Normal,
                                color = color,
                                maxLines = 1,
                                text = i.toString().plus(".")
                            )
                            Text(
                                modifier = Modifier
                                    .padding(0.dp, padding.dp)
                                    .basicMarquee(iterations = Int.MAX_VALUE)
                                    .weight(1F),
                                textAlign = TextAlign.Start,
                                fontSize = TextUnit(StringUtils.scaledSize(15F, instance), TextUnitType.Sp),
                                fontWeight = if (isClosest) FontWeight.Bold else FontWeight.Normal,
                                color = color,
                                maxLines = 1,
                                text = stopStr
                            )
                            Column (
                                modifier = Modifier
                                    .requiredWidth(etaTextWidth.dp)
                                    .offset(0.dp, -TextUnit(2F, TextUnitType.Sp).dp)
                                    .align(Alignment.CenterVertically),
                                horizontalAlignment = Alignment.End,
                                verticalArrangement = Arrangement.Center
                            ) {
                                if (eta != null && !eta!!.isConnectionError) {
                                    if (eta!!.nextScheduledBus < 0 || eta!!.nextScheduledBus > 60) {
                                        Icon(
                                            modifier = Modifier
                                                .size(TextUnit(StringUtils.scaledSize(16F, instance), TextUnitType.Sp).clamp(max = StringUtils.scaledSize(18F, instance).dp).dp)
                                                .offset(0.dp, TextUnit(StringUtils.scaledSize(3F, instance), TextUnitType.Sp).clamp(max = StringUtils.scaledSize(4F, instance).dp).dp),
                                            painter = painterResource(R.drawable.baseline_schedule_24),
                                            contentDescription = if (Shared.language == "en") "No scheduled departures at this moment" else "暫時沒有預定班次",
                                            tint = Color(0xFF798996),
                                        )
                                    } else {
                                        val text1 = (if (eta!!.nextScheduledBus == 0L) "-" else eta!!.nextScheduledBus.toString())
                                        val text2 = if (Shared.language == "en") "Min." else "分鐘"
                                        val span1 = SpannableString(text1)
                                        val size1 = UnitUtils.spToPixels(instance, clampSp(instance, StringUtils.scaledSize(14F, instance), dpMax = StringUtils.scaledSize(15F, instance))).roundToInt()
                                        span1.setSpan(AbsoluteSizeSpan(size1), 0, text1.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
                                        val span2 = SpannableString(text2)
                                        val size2 = UnitUtils.spToPixels(instance, clampSp(instance, StringUtils.scaledSize(7F, instance), dpMax = StringUtils.scaledSize(8F, instance))).roundToInt()
                                        span2.setSpan(AbsoluteSizeSpan(size2), 0, text2.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
                                        Text(
                                            modifier = Modifier.fillMaxWidth(),
                                            textAlign = TextAlign.End,
                                            fontSize = TextUnit(14F, TextUnitType.Sp),
                                            color = Color(0xFFAAC3D5),
                                            lineHeight = TextUnit(7F, TextUnitType.Sp),
                                            maxLines = 2,
                                            text = SpannableString(TextUtils.concat(span1, "\n", span2)).toAnnotatedString(instance, 14F)
                                        )
                                    }
                                }
                            }
                        }
                    }
                    Spacer(
                        modifier = Modifier
                            .padding(25.dp, 0.dp)
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(Color(0xFF333333))
                    )
                }
            }
            items(5) {
                Spacer(modifier = Modifier.size(7.dp))
            }
        }
    }
}