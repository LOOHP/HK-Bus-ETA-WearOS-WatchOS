/*
 * This file is part of HKBusETA.
 *
 * Copyright (C) 2023. LoohpJames <jamesloohp@gmail.com>
 * Copyright (C) 2023. Contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.a
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 *
 */

package com.loohp.hkbuseta.common.shared

import co.touchlab.stately.collections.ConcurrentMutableMap
import co.touchlab.stately.concurrency.AtomicReference
import co.touchlab.stately.concurrency.Lock
import co.touchlab.stately.concurrency.value
import co.touchlab.stately.concurrency.withLock
import com.loohp.hkbuseta.common.appcontext.AppActiveContext
import com.loohp.hkbuseta.common.appcontext.AppContext
import com.loohp.hkbuseta.common.appcontext.AppIntent
import com.loohp.hkbuseta.common.appcontext.AppIntentFlag
import com.loohp.hkbuseta.common.appcontext.AppScreen
import com.loohp.hkbuseta.common.objects.Coordinates
import com.loohp.hkbuseta.common.objects.FavouriteResolvedStop
import com.loohp.hkbuseta.common.objects.FavouriteRouteStop
import com.loohp.hkbuseta.common.objects.GMBRegion
import com.loohp.hkbuseta.common.objects.KMBSubsidiary
import com.loohp.hkbuseta.common.objects.LastLookupRoute
import com.loohp.hkbuseta.common.objects.Operator
import com.loohp.hkbuseta.common.objects.Route
import com.loohp.hkbuseta.common.objects.RouteListType
import com.loohp.hkbuseta.common.objects.RouteSearchResultEntry
import com.loohp.hkbuseta.common.objects.RouteSortMode
import com.loohp.hkbuseta.common.objects.StopInfo
import com.loohp.hkbuseta.common.objects.getDisplayRouteNumber
import com.loohp.hkbuseta.common.objects.getRouteKey
import com.loohp.hkbuseta.common.objects.gmbRegion
import com.loohp.hkbuseta.common.objects.putExtra
import com.loohp.hkbuseta.common.objects.resolveStop
import com.loohp.hkbuseta.common.objects.uniqueKey
import com.loohp.hkbuseta.common.utils.Colored
import com.loohp.hkbuseta.common.utils.FormattedText
import com.loohp.hkbuseta.common.utils.Immutable
import com.loohp.hkbuseta.common.utils.SmallSize
import com.loohp.hkbuseta.common.utils.asFormattedText
import com.loohp.hkbuseta.common.utils.currentLocalDateTime
import com.loohp.hkbuseta.common.utils.currentTimeMillis
import com.rickclephas.kmp.nativecoroutines.NativeCoroutinesState
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.math.absoluteValue
import kotlin.time.DurationUnit
import kotlin.time.toDuration


expect val JOINT_OPERATED_COLOR_REFRESH_RATE: Long

@Immutable
object Shared {

    const val ETA_UPDATE_INTERVAL: Int = 15000

    val MTR_ROUTE_FILTER: (Route) -> Boolean = { r -> r.bound.containsKey(Operator.MTR) }
    val RECENT_ROUTE_FILTER: (Route, Operator) -> Boolean = { r, c ->
        getFavoriteAndLookupRouteIndex(r.routeNumber, c, when (c) {
            Operator.GMB -> r.gmbRegion!!.name
            Operator.NLB -> r.nlbId
            else -> ""
        }) < Int.MAX_VALUE
    }

    fun invalidateCache(context: AppContext) {
        try {
            Registry.invalidateCache(context)
        } catch (_: Throwable) {}
    }

    private val backgroundUpdateScheduler: AtomicReference<(AppContext, Long) -> Unit> = AtomicReference { _, _ -> }

    fun provideBackgroundUpdateScheduler(runnable: (AppContext, Long) -> Unit) {
        backgroundUpdateScheduler.value = runnable
    }

    fun scheduleBackgroundUpdateService(context: AppContext, time: Long) {
        backgroundUpdateScheduler.value.invoke(context, time)
    }

    fun ensureRegistryDataAvailable(context: AppActiveContext): Boolean {
        return if (!Registry.hasInstanceCreated() || Registry.getInstanceNoUpdateCheck(context).state.value.isProcessing) {
            val intent = AppIntent(context, AppScreen.MAIN)
            intent.addFlags(AppIntentFlag.NEW_TASK, AppIntentFlag.CLEAR_TASK)
            context.startActivity(intent)
            context.finishAffinity()
            false
        } else {
            true
        }
    }

    internal val kmbSubsidiary: Map<String, KMBSubsidiary> = HashMap()

    fun setKmbSubsidiary(values: Map<KMBSubsidiary, List<String>>) {
        kmbSubsidiary as MutableMap
        kmbSubsidiary.clear()
        for ((type, list) in values) {
            for (route in list) {
                kmbSubsidiary.put(route, type)
            }
        }
    }

    private val jointOperatedColorFraction: MutableStateFlow<Float> = MutableStateFlow(0F)
    private const val jointOperatorColorTransitionTime: Long = 5000
    @NativeCoroutinesState
    val jointOperatedColorFractionState: StateFlow<Float> = jointOperatedColorFraction

    init {
        CoroutineScope(Dispatchers.Default).launch {
            while (true) {
                val startTime = currentTimeMillis()
                while (currentTimeMillis() - startTime < jointOperatorColorTransitionTime) {
                    val progress = (currentTimeMillis() - startTime).toFloat() / jointOperatorColorTransitionTime
                    jointOperatedColorFraction.value = progress
                    delay(JOINT_OPERATED_COLOR_REFRESH_RATE)
                }
                val yellowToRedStartTime = currentTimeMillis()
                while (currentTimeMillis() - yellowToRedStartTime < jointOperatorColorTransitionTime) {
                    val progress = (currentTimeMillis() - yellowToRedStartTime).toFloat() / jointOperatorColorTransitionTime
                    jointOperatedColorFraction.value = 1F - progress
                    delay(JOINT_OPERATED_COLOR_REFRESH_RATE)
                }
            }
        }
    }

    fun Registry.ETAQueryResult?.getResolvedText(seq: Int, clockTimeMode: Boolean, context: AppContext): FormattedText {
        return (this?.getLine(seq)?.let { if (clockTimeMode && it.etaRounded >= 0) "${context.formatTime(currentLocalDateTime(it.etaRounded.toDuration(DurationUnit.MINUTES)))} ".asFormattedText(Colored(0xFFFFFF00)) + it.text else it.text }?: if (seq == 1) (if (language == "en") "Updating" else "更新中").asFormattedText() else "".asFormattedText())
    }

    fun Registry.MergedETAQueryResult<Pair<FavouriteResolvedStop, FavouriteRouteStop>>?.getResolvedText(seq: Int, clockTimeMode: Boolean, context: AppContext): Pair<Pair<FavouriteResolvedStop, FavouriteRouteStop>?, FormattedText> {
        if (this == null) {
            return null to if (seq == 1) (if (language == "en") "Updating" else "更新中").asFormattedText() else "".asFormattedText()
        }
        val line = this[seq]
        val lineRoute = line.first?.let { it.second.co.getDisplayRouteNumber(it.second.route.routeNumber, true) }
        val noRouteNumber = lineRoute == null ||
                (1..3).all { this[it].first?.second?.route?.routeNumber.let { route -> route == null || route == line.first?.second?.route?.routeNumber } } ||
                this.allKeys.all { it.second.co == Operator.MTR } ||
                this.mergedCount <= 1
        return line.first to (if (noRouteNumber) "".asFormattedText() else "$lineRoute > ".asFormattedText(SmallSize))
            .plus(if (clockTimeMode && line.second.etaRounded >= 0) "${context.formatTime(currentLocalDateTime(line.second.etaRounded.toDuration(DurationUnit.MINUTES)))} ".asFormattedText(Colored(0xFFFFFF00)) else "".asFormattedText())
            .plus(line.second.text)
    }

    fun getMtrLineSortingIndex(lineName: String): Int {
        return when (lineName) {
            "AEL" -> 0
            "TCL" -> 1
            "TML" -> 4
            "TKL" -> 9
            "EAL" -> 3
            "SIL" -> 5
            "TWL" -> 8
            "ISL" -> 6
            "KTL" -> 7
            "DRL" -> 2
            else -> 10
        }
    }

    fun getMtrLineName(lineName: String): String {
        return getMtrLineName(lineName, lineName)
    }

    fun getMtrLineName(lineName: String, orElse: String): String {
        return if (language == "en") when (lineName) {
            "AEL" -> "Airport Express"
            "TCL" -> "Tung Chung Line"
            "TML" -> "Tuen Ma Line"
            "TKL" -> "Tseung Kwan O Line"
            "EAL" -> "East Rail Line"
            "SIL" -> "South Island Line"
            "TWL" -> "Tsuen Wan Line"
            "ISL" -> "Island Line"
            "KTL" -> "Kwun Tong Line"
            "DRL" -> "Disneyland Resort Line"
            else -> orElse
        } else when (lineName) {
            "AEL" -> "機場快綫"
            "TCL" -> "東涌綫"
            "TML" -> "屯馬綫"
            "TKL" -> "將軍澳綫"
            "EAL" -> "東鐵綫"
            "SIL" -> "南港島綫"
            "TWL" -> "荃灣綫"
            "ISL" -> "港島綫"
            "KTL" -> "觀塘綫"
            "DRL" -> "迪士尼綫"
            else -> orElse
        }
    }

    var language = "zh"
    var clockTimeMode = false
    var lrtDirectionMode = false

    private val suggestedMaxFavouriteRouteStop = MutableStateFlow(0)
    private val currentMaxFavouriteRouteStop = MutableStateFlow(0)
    private val favouriteRouteStopLock: Lock = Lock()
    val favoriteRouteStops: Map<Int, FavouriteRouteStop> = ConcurrentMutableMap()

    fun getFavouriteRouteStop(index: Int): FavouriteRouteStop? {
        return favoriteRouteStops[index]
    }

    val shouldShowFavListRouteView: Boolean get() = (favoriteRouteStops.keys.maxOrNull()?: 0) > 2

    fun sortedForListRouteView(instance: AppContext, origin: Coordinates?): List<RouteSearchResultEntry> {
        return favoriteRouteStops.entries.asSequence()
            .sortedBy { it.key }
            .map { (_, fav) ->
                val (_, stopId, stop, route) = fav.resolveStop(instance) { origin }
                val routeEntry = RouteSearchResultEntry(route.getRouteKey(instance)!!, route, fav.co, StopInfo(stopId, stop, 0.0, fav.co), null, false)
                routeEntry.strip()
                routeEntry
            }
            .distinctBy { routeEntry -> routeEntry.uniqueKey }
            .toList()
    }

    fun updateFavoriteRouteStops(mutation: (MutableMap<Int, FavouriteRouteStop>) -> Unit) {
        favouriteRouteStopLock.withLock {
            mutation.invoke(favoriteRouteStops as MutableMap)
            val max = favoriteRouteStops.maxOfOrNull { it.key }?: 0
            currentMaxFavouriteRouteStop.value = max.coerceAtLeast(8)
            suggestedMaxFavouriteRouteStop.value = (max + 1).coerceIn(8, 30)
        }
    }

    @NativeCoroutinesState
    val suggestedMaxFavouriteRouteStopState: StateFlow<Int> = suggestedMaxFavouriteRouteStop
    @NativeCoroutinesState
    val currentMaxFavouriteRouteStopState: StateFlow<Int> = currentMaxFavouriteRouteStop

    private const val LAST_LOOKUP_ROUTES_MEM_SIZE = 50
    private val lastLookupRouteLock: Lock = Lock()
    private val lastLookupRoutes: ArrayDeque<LastLookupRoute> = ArrayDeque(LAST_LOOKUP_ROUTES_MEM_SIZE)

    fun addLookupRoute(routeNumber: String, co: Operator, meta: String) {
        addLookupRoute(LastLookupRoute(routeNumber, co, meta))
    }

    fun addLookupRoute(data: LastLookupRoute) {
        lastLookupRouteLock.withLock {
            lastLookupRoutes.removeAll { it == data }
            lastLookupRoutes.add(data)
            while (lastLookupRoutes.size > LAST_LOOKUP_ROUTES_MEM_SIZE) {
                lastLookupRoutes.removeFirst()
            }
        }
    }

    fun clearLookupRoute() {
        lastLookupRoutes.clear()
    }

    fun getLookupRoutes(): List<LastLookupRoute> {
        lastLookupRouteLock.withLock {
            return ArrayList(lastLookupRoutes)
        }
    }

    fun getFavoriteAndLookupRouteIndex(routeNumber: String, co: Operator, meta: String): Int {
        for ((index, route) in favoriteRouteStops) {
            val routeData = route.route
            if (routeData.routeNumber == routeNumber && route.co == co && (co != Operator.GMB || routeData.gmbRegion == meta.gmbRegion) && (co != Operator.NLB || routeData.nlbId == meta)) {
                return index
            }
        }
        lastLookupRouteLock.withLock {
            for ((index, data) in lastLookupRoutes.withIndex()) {
                val (lookupRouteNumber, lookupCo, lookupMeta) = data
                if (lookupRouteNumber == routeNumber && lookupCo == co && ((co != Operator.GMB && co != Operator.NLB) || meta == lookupMeta)) {
                    return (lastLookupRoutes.size - index) + 8
                }
            }
        }
        return Int.MAX_VALUE
    }

    fun hasFavoriteAndLookupRoute(): Boolean {
        return favoriteRouteStops.isNotEmpty() || lastLookupRoutes.isNotEmpty()
    }

    val routeSortModePreference: Map<RouteListType, RouteSortMode> = ConcurrentMutableMap()

    @Suppress("NAME_SHADOWING")
    fun handleLaunchOptions(instance: AppActiveContext, stopId: String?, co: Operator?, index: Int?, stop: Any?, route: Any?, listStopRoute: ByteArray?, listStopScrollToStop: String?, listStopShowEta: Boolean?, listStopIsAlightReminder: Boolean?, queryKey: String?, queryRouteNumber: String?, queryBound: String?, queryCo: Operator?, queryDest: String?, queryGMBRegion: GMBRegion?, queryStop: String?, queryStopIndex: Int, queryStopDirectLaunch: Boolean, orElse: () -> Unit) {
        CoroutineScope(Dispatchers.Default).launch {
            val stop = stop
            val route = route
            val listStopRoute = listStopRoute
            var queryRouteNumber = queryRouteNumber
            var queryCo = queryCo
            var queryBound = queryBound
            var queryGMBRegion = queryGMBRegion

            if (stopId != null && co != null && (stop is String || stop is ByteArray) && (route is String || route is ByteArray)) {
                val routeParsed = if (route is String) Route.deserialize(Json.decodeFromString<JsonObject>(route)) else runBlocking { Route.deserialize(ByteReadChannel(route as ByteArray)) }
                Registry.getInstance(instance).findRoutes(routeParsed.routeNumber, true) { it ->
                    val bound = it.bound
                    if (!bound.containsKey(co) || bound[co] != routeParsed.bound[co]) {
                        return@findRoutes false
                    }
                    val stops = it.stops[co]?: return@findRoutes false
                    return@findRoutes stops.contains(stopId)
                }.firstOrNull()?.let {
                    val intent = AppIntent(instance, AppScreen.LIST_STOPS)
                    intent.putExtra("shouldRelaunch", false)
                    intent.putExtra("route", it)
                    intent.putExtra("scrollToStop", stopId)
                    instance.startActivity(intent)
                }

                val intent = AppIntent(instance, AppScreen.ETA)
                intent.putExtra("shouldRelaunch", false)
                intent.putExtra("stopId", stopId)
                intent.putExtra("co", co.name)
                intent.putExtra("index", index!!)
                if (stop is String) {
                    intent.putExtra("stopStr", stop)
                } else {
                    intent.putExtra("stop", stop as ByteArray)
                }
                if (route is String) {
                    intent.putExtra("routeStr", route)
                } else {
                    intent.putExtra("route", route as ByteArray)
                }
                instance.startActivity(intent)
                instance.finish()
            } else if (listStopRoute != null && listStopScrollToStop != null && listStopShowEta != null && listStopIsAlightReminder != null) {
                val intent = AppIntent(instance, AppScreen.LIST_STOPS)
                intent.putExtra("route", listStopRoute)
                intent.putExtra("scrollToStop", listStopScrollToStop)
                intent.putExtra("showEta", listStopShowEta)
                intent.putExtra("isAlightReminder", listStopIsAlightReminder)
                instance.startActivity(intent)
                instance.finish()
            } else if (queryRouteNumber != null || queryKey != null) {
                if (queryKey != null) {
                    val routeNumber = Regex("^([0-9a-zA-Z]+)").find(queryKey)?.groupValues?.getOrNull(1)
                    val nearestRoute = Registry.getInstance(instance).findRouteByKey(queryKey, routeNumber)
                    queryRouteNumber = nearestRoute!!.routeNumber
                    queryCo = if (nearestRoute.isKmbCtbJoint) Operator.KMB else nearestRoute.co[0]
                    queryBound = if (queryCo == Operator.NLB) nearestRoute.nlbId else nearestRoute.bound[queryCo]
                    queryGMBRegion = nearestRoute.gmbRegion
                }

                instance.startActivity(AppIntent(instance, AppScreen.TITLE))

                val result = Registry.getInstance(instance).findRoutes(queryRouteNumber?: "", true)
                if (result.isNotEmpty()) {
                    var filteredResult = result.asSequence().filter {
                        return@filter when (queryCo) {
                            Operator.NLB -> it.co == queryCo && (queryBound == null || it.route!!.nlbId == queryBound)
                            Operator.GMB -> {
                                val r = it.route!!
                                it.co == queryCo && (queryBound == null || r.bound[queryCo] == queryBound) && r.gmbRegion == queryGMBRegion
                            }
                            else -> (queryCo == null || it.co == queryCo) && (queryBound == null || it.route!!.bound[queryCo] == queryBound)
                        }
                    }.toList()
                    if (queryDest != null) {
                        val destFiltered = filteredResult.asSequence().filter {
                            val dest = it.route!!.dest
                            return@filter queryDest == dest.zh || queryDest == dest.en
                        }.toList()
                        if (destFiltered.isNotEmpty()) {
                            filteredResult = destFiltered
                        }
                    }
                    if (filteredResult.isEmpty()) {
                        val intent = AppIntent(instance, AppScreen.LIST_ROUTES)
                        intent.putExtra("result", result.asSequence().map { it.deepClone() })
                        instance.startActivity(intent)
                    } else {
                        val intent = AppIntent(instance, AppScreen.LIST_ROUTES)
                        intent.putExtra("result", filteredResult.asSequence().map { it.deepClone() })
                        instance.startActivity(intent)

                        val it = filteredResult[0]
                        val meta = when (it.co) {
                            Operator.GMB -> it.route!!.gmbRegion!!.name
                            Operator.NLB -> it.route!!.nlbId
                            else -> ""
                        }
                        Registry.getInstance(instance).addLastLookupRoute(queryRouteNumber, it.co, meta, instance)

                        if (queryStop != null) {
                            val intent2 = AppIntent(instance, AppScreen.LIST_STOPS)
                            intent2.putExtra("route", it)
                            intent2.putExtra("scrollToStop", queryStop)
                            instance.startActivity(intent2)

                            if (queryStopDirectLaunch) {
                                val stops = Registry.getInstance(instance).getAllStops(queryRouteNumber!!, queryBound!!, queryCo!!, queryGMBRegion)
                                stops.withIndex().filter { it.value.stopId == queryStop }.minByOrNull { (queryStopIndex - it.index).absoluteValue }?.let { (i, stopData) ->
                                    val intent3 = AppIntent(instance, AppScreen.ETA)
                                    intent3.putExtra("stopId", stopData.stopId)
                                    intent3.putExtra("co", queryCo)
                                    intent3.putExtra("index", i + 1)
                                    intent3.putExtra("stop", stopData.stop)
                                    intent3.putExtra("route", stopData.route)
                                    instance.startActivity(intent3)
                                }
                            }
                        } else if (filteredResult.size == 1) {
                            val intent2 = AppIntent(instance, AppScreen.LIST_STOPS)
                            intent2.putExtra("route", it)
                            instance.startActivity(intent2)
                        }
                    }
                }
                instance.finishAffinity()
            } else {
                orElse.invoke()
            }
        }
    }

}