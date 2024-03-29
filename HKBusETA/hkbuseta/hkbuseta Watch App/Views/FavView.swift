//
//  EtaView.swift
//  hkbuseta Watch App
//
//  Created by LOOHP on 22/12/2023.
//

import SwiftUI
import shared
import KMPNativeCoroutinesCore
import KMPNativeCoroutinesRxSwift
import KMPNativeCoroutinesAsync
import KMPNativeCoroutinesCombine
import RxSwift

struct FavView: AppScreenView {
    
    @StateObject private var jointOperatedColorFraction = FlowStateObservable(defaultValue: KotlinFloat(float: Shared().jointOperatedColorFractionState), nativeFlow: Shared().jointOperatedColorFractionStateFlow)
    
    @StateObject private var maxFavItems = FlowStateObservable(defaultValue: KotlinInt(int: Shared().currentMaxFavouriteRouteStopState), nativeFlow: Shared().currentMaxFavouriteRouteStopStateFlow)
    @State private var showRouteListViewButton = false
    
    @Environment(\.isLuminanceReduced) var ambientMode
    
    let etaTimer = Timer.publish(every: Double(Shared().ETA_UPDATE_INTERVAL) / 1000, on: .main, in: .common).autoconnect()
    @State private var etaActive: [Int] = []
    @State private var etaResults: ETAResultsContainer<KotlinInt> = ETAResultsContainer()
    
    let deleteTimer = Timer.publish(every: 0.2, on: .main, in: .common).autoconnect()
    
    @ObservedObject private var locationManager = SingleLocationManager()
    @State private var origin: LocationResult? = nil
    
    @State private var favRouteStops: [Int: FavouriteRouteStop] = [:]
    @State private var deleteStates: [Int: Double] = [:]
    
    private let appContext: AppActiveContextWatchOS
    
    init(appContext: AppActiveContextWatchOS, data: [String: Any], storage: KotlinMutableDictionary<NSString, AnyObject>) {
        self.appContext = appContext
    }
    
    var body: some View {
        ScrollViewReader { value in
            ScrollView(.vertical) {
                LazyVStack(alignment: .center, spacing: 1.scaled(appContext)) {
                    Spacer().frame(fixedSize: 10.scaled(appContext))
                    Text(Shared().language == "en" ? "Favourite Routes" : "最喜愛路線")
                        .multilineTextAlignment(.center)
                        .foregroundColor(colorInt(0xFFFFFFFF).asColor().adjustBrightness(percentage: ambientMode ? 0.7 : 1))
                        .lineLimit(2)
                        .autoResizing(maxSize: 23.scaled(appContext, true), weight: .bold)
                    Spacer().frame(height: 5.scaled(appContext))
                    Text(Shared().language == "en" ? "Routes can be displayed in Tiles" : "路線可在資訊方塊中顯示")
                        .font(.system(size: 10.scaled(appContext, true)))
                        .foregroundColor(colorInt(0xFFFFFFFF).asColor().adjustBrightness(percentage: ambientMode ? 0.7 : 1))
                        .multilineTextAlignment(.center)
                        .padding(.horizontal, 20.scaled(appContext))
                    Spacer().frame(fixedSize: 10.scaled(appContext))
                    if showRouteListViewButton {
                        Button(action: {
                            let data = newAppDataConatiner()
                            data["usingGps"] = !locationManager.authorizationDenied
                            appContext.startActivity(appIntent: newAppIntent(appContext, AppScreen.favRouteListView, data))
                        }) {
                            Text(Shared().language == "en" ? "Route List View" : "路線一覽列表")
                                .foregroundColor(colorInt(0xFFFFFFFF).asColor().adjustBrightness(percentage: ambientMode ? 0.7 : 1))
                                .font(.system(size: 17.scaled(appContext, true), weight: .bold))
                        }
                        .frame(width: 160.scaled(appContext), height: 35.scaled(appContext))
                        .clipShape(RoundedRectangle(cornerRadius: 25))
                        .edgesIgnoringSafeArea(.all)
                        Spacer().frame(fixedSize: 10.scaled(appContext))
                    }
                    ForEach(1...Int(truncating: maxFavItems.state), id: \.self) { index in
                        FavButton(favIndex: index).id(index)
                        Spacer().frame(fixedSize: 5.scaled(appContext))
                    }
                }
            }
        }
        .onAppear {
            maxFavItems.subscribe()
        }
        .onDisappear {
            jointOperatedColorFraction.unsubscribe()
            maxFavItems.unsubscribe()
        }
        .onChange(of: maxFavItems.state) { _ in
            showRouteListViewButton = Shared().shouldShowFavListRouteView
        }
        .onAppear {
            showRouteListViewButton = Shared().shouldShowFavListRouteView
        }
        .onReceive(etaTimer) { _ in
            for favIndex in etaActive {
                let currentFavRouteStop = favRouteStops[favIndex] ?? {
                    let s = Shared().getFavouriteRouteStop(index: favIndex.asInt32())
                    DispatchQueue.main.async {
                        favRouteStops[favIndex] = s
                    }
                    return s
                }()
                if currentFavRouteStop != nil {
                    fetchEta(appContext: appContext, stopId: currentFavRouteStop!.stopId, stopIndex: currentFavRouteStop!.index, co: currentFavRouteStop!.co, route: currentFavRouteStop!.route) { etaResults.set(key: favIndex.asKt(), result: $0) }
                }
            }
        }
        .onReceive(deleteTimer) { _ in
            for (favIndex, time) in deleteStates {
                let newTime = time - 0.2
                DispatchQueue.main.async {
                    if newTime > 0 {
                        deleteStates[favIndex] = newTime
                    } else {
                        deleteStates.removeValue(forKey: favIndex)
                    }
                }
            }
        }
        .onChange(of: locationManager.readyForRequest) { _ in
            locationManager.requestLocation()
        }
        .onAppear {
            if locationManager.readyForRequest {
                locationManager.requestLocation()
            } else if !locationManager.authorizationDenied {
                locationManager.requestPermission()
            }
        }
        .onChange(of: locationManager.isLocationFetched) { _ in
            if locationManager.location != nil {
                origin = locationManager.location!.coordinate.toLocationResult()
            }
        }
    }
    
    func FavButton(favIndex: Int) -> some View {
        let currentFavRouteStop = favRouteStops[favIndex] ?? {
            let s = Shared().getFavouriteRouteStop(index: favIndex.asInt32())
            DispatchQueue.main.async {
                favRouteStops[favIndex] = s
            }
            return s
        }()
        let anyTileUses = Tiles().getTileUseState(index: favIndex.asInt32())
        let deleteState = deleteStates[favIndex] ?? 0.0
        return Button(action: {}) {
            HStack(alignment: .top, spacing: 0) {
                ZStack(alignment: .leading) {
                    Text("").frame(width: 32.scaled(appContext, true))
                    ZStack {
                        Circle()
                            .fill(currentFavRouteStop != nil ? colorInt(0xFF3D3D3D).asColor() : colorInt(0xFF131313).asColor())
                            .frame(width: 30.scaled(appContext), height: 30.scaled(appContext))
                            .overlay {
                                if deleteState > 0.0 {
                                    Circle()
                                        .trim(from: 0.0, to: deleteState / 5.0)
                                        .rotation(.degrees(-90))
                                        .stroke(colorInt(0xFFFF0000).asColor(), style: StrokeStyle(lineWidth: 2.scaled(appContext), lineCap: .butt))
                                        .animation(.linear, value: deleteState)
                                }
                            }
                        if deleteState > 0.0 {
                            Image(systemName: "xmark")
                                .resizable()
                                .aspectRatio(contentMode: .fit)
                                .frame(width: 13.scaled(appContext, true), height: 13.scaled(appContext, true))
                                .foregroundColor(colorInt(0xFFFF0000).asColor())
                        } else {
                            Text("\(favIndex)")
                                .font(.system(size: 17.scaled(appContext, true), weight: .bold))
                                .foregroundColor(currentFavRouteStop != nil ? colorInt(0xFFFFFF00).asColor() : colorInt(0xFF444444).asColor())
                        }
                    }
                }
                .padding(10)
                VStack(alignment: .leading, spacing: 1.scaled(appContext)) {
                    if currentFavRouteStop == nil {
                        HStack(alignment: .center) {
                            Text(Shared().language == "en" ? "No Route Selected" : "未有設置路線")
                                .font(.system(size: 16.scaled(appContext, true), weight: .bold))
                                .foregroundColor(colorInt(0xFF505050).asColor())
                                .lineLimit(2)
                                .lineSpacing(0)
                                .multilineTextAlignment(.leading)
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .fixedSize(horizontal: false, vertical: true)
                        }.frame(maxHeight: .infinity)
                    } else {
                        let resolvedStop = currentFavRouteStop!.resolveStop(context: appContext) { origin?.location }
                        let stopName = resolvedStop.stop.name
                        let index = resolvedStop.index
                        let route = resolvedStop.route
                        let kmbCtbJoint = route.isKmbCtbJoint
                        let co = currentFavRouteStop!.co
                        let routeNumber = route.routeNumber
                        let gpsStop = currentFavRouteStop!.favouriteStopMode.isRequiresLocation
                        let destName = registry(appContext).getStopSpecialDestinations(stopId: currentFavRouteStop!.stopId, co: currentFavRouteStop!.co, route: route, prependTo: true)
                        let color = operatorColor(currentFavRouteStop!.co.getColor(routeNumber: routeNumber, elseColor: 0xFFFFFFFF as Int64), Operator.Companion().CTB.getOperatorColor(elseColor: 0xFFFFFFFF as Int64), jointOperatedColorFraction.state.floatValue) { _ in kmbCtbJoint }
                        let operatorName = currentFavRouteStop!.co.getDisplayName(routeNumber: routeNumber, kmbCtbJoint: route.isKmbCtbJoint, language: Shared().language, elseName: "???")
                        let mainText = "\(operatorName) \(currentFavRouteStop!.co.getDisplayRouteNumber(routeNumber: routeNumber, shortened: false))"
                        let routeText = destName.get(language: Shared().language)
                        
                        let subText = {
                            var text = ((co.isTrain ? "" : "\(index). ") + stopName.get(language: Shared().language)).asAttributedString()
                            if gpsStop {
                                text += (Shared().language == "en" ? " - Closest" : " - 最近").asAttributedString(color: colorInt(0xFFFFE496).asColor(), fontSize: 14 * 0.8)
                            }
                            return text
                        }()
                        
                        VStack(alignment: .leading, spacing: 0) {
                            MarqueeText(
                                text: mainText,
                                font: UIFont.systemFont(ofSize: 19.scaled(appContext, true), weight: .bold),
                                startDelay: 2,
                                alignment: .bottomLeading
                            )
                            .foregroundColor(color.asColor())
                            .lineLimit(1)
                            .onAppear {
                                if kmbCtbJoint {
                                    jointOperatedColorFraction.subscribe()
                                }
                            }
                            MarqueeText(
                                text: routeText,
                                font: UIFont.systemFont(ofSize: 17.scaled(appContext, true)),
                                startDelay: 2,
                                alignment: .bottomLeading
                            )
                            .foregroundColor(.white)
                            .lineLimit(1)
                            Spacer().frame(fixedSize: 3.scaled(appContext))
                            MarqueeText(
                                text: subText,
                                font: UIFont.systemFont(ofSize: 14.scaled(appContext, true)),
                                startDelay: 2,
                                alignment: .bottomLeading
                            )
                            .foregroundColor(.white)
                            .lineLimit(1)
                        }
                    }
                }.padding(.vertical, 5)
            }
            .overlay(alignment: .leading) {
                ZStack(alignment: .bottomLeading) {
                    Text("").frame(maxWidth: .infinity, maxHeight: .infinity)
                    if currentFavRouteStop != nil {
                        ETAElement(favIndex: favIndex, currentFavRouteStop: currentFavRouteStop!)
                            .padding(.bottom, 5.scaled(appContext))
                            .padding(.leading, 10)
                    }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .buttonStyle(PlainButtonStyle())
        .background { deleteState > 0.0 ? colorInt(0xFF633A3A).asColor() : colorInt(0xFF1A1A1A).asColor() }
        .animation(.linear(duration: 0.25), value: deleteState)
        .frame(minWidth: 178.0.scaled(appContext), maxWidth: 178.0.scaled(appContext), minHeight: 47.0.scaled(appContext))
        .clipShape(RoundedRectangle(cornerRadius: 10))
        .tileStateBorder(anyTileUses, 10)
        .simultaneousGesture(
            LongPressGesture()
                .onEnded { _ in
                    if deleteState <= 0.0 {
                        playHaptics()
                        appContext.showToastText(text: Shared().language == "en" ? "Click again to confirm delete" : "再次點擊確認刪除", duration: ToastDuration.short_)
                        deleteStates[favIndex] = 5.0
                    }
                }
        )
        .highPriorityGesture(
            TapGesture()
                .onEnded { _ in
                    if deleteState > 0.0 {
                        if (registry(appContext).hasFavouriteRouteStop(favoriteIndex: favIndex.asInt32())) {
                            registry(appContext).clearFavouriteRouteStop(favoriteIndex: favIndex.asInt32(), context: appContext)
                            appContext.showToastText(text: Shared().language == "en" ? "Cleared Favourite Route \(favIndex)" : "已清除最喜愛路線\(favIndex)", duration: ToastDuration.short_)
                        }
                        DispatchQueue.main.async {
                            deleteStates.removeValue(forKey: favIndex)
                            favRouteStops[favIndex] = Shared().getFavouriteRouteStop(index: favIndex.asInt32())
                        }
                    } else {
                        if currentFavRouteStop != nil {
                            let co = currentFavRouteStop!.co
                            let resolvedStop = currentFavRouteStop!.resolveStop(context: appContext) { origin?.location }
                            let index = resolvedStop.index
                            let stopId = resolvedStop.stopId
                            let stop = resolvedStop.stop
                            let route = resolvedStop.route
                            let entry = registry(appContext).findRoutes(input: route.routeNumber, exact: true, predicate: {
                                let bound = $0.bound
                                if !bound.keys.contains(where: { $0 == co }) || bound[co] != route.bound[co] {
                                    return false.asKt()
                                }
                                let stops = $0.stops[co]
                                if stops == nil {
                                    return false.asKt()
                                }
                                return stops!.contains { $0 == stopId }.asKt()
                            })
                            if !entry.isEmpty {
                                let data = newAppDataConatiner()
                                data["route"] = entry[0]
                                data["scrollToStop"] = stopId
                                appContext.startActivity(appIntent: newAppIntent(appContext, AppScreen.listStops, data))
                            }
                            
                            let data = newAppDataConatiner()
                            data["stopId"] = stopId
                            data["co"] = co
                            data["index"] = index
                            data["stop"] = stop
                            data["route"] = route
                            appContext.startActivity(appIntent: newAppIntent(appContext, AppScreen.eta, data))
                        }
                    }
                }
        )
    }
    
    func ETAElement(favIndex: Int, currentFavRouteStop: FavouriteRouteStop) -> some View {
        ZStack {
            FavEtaView(appContext: appContext, etaState: etaResults.getState(key: favIndex.asKt()))
        }
        .onAppear {
            etaActive.append(favIndex)
            fetchEta(appContext: appContext, stopId: currentFavRouteStop.stopId, stopIndex: currentFavRouteStop.index, co: currentFavRouteStop.co, route: currentFavRouteStop.route) { etaResults.set(key: favIndex.asKt(), result: $0) }
        }
        .onDisappear {
            etaActive.removeAll(where: { $0 == favIndex })
        }
    }

}

struct FavEtaView: View {
    
    @StateObject private var etaState: FlowStateObservable<Registry.ETAQueryResult?>
    
    private let appContext: AppActiveContextWatchOS
    
    init(appContext: AppActiveContextWatchOS, etaState: ETAResultsState) {
        self.appContext = appContext
        self._etaState = StateObject(wrappedValue: FlowStateObservable(defaultValue: etaState.state, nativeFlow: etaState.stateFlow))
    }
    
    var body: some View {
        ZStack {
            let optEta = etaState.state
            if optEta != nil {
                let eta = optEta!
                if !eta.isConnectionError {
                    if !(0..<60).contains(eta.nextScheduledBus) {
                        if eta.isMtrEndOfLine {
                            Image(systemName: "arrow.forward.to.line.circle")
                                .font(.system(size: 17.scaled(appContext, true)))
                                .foregroundColor(colorInt(0xFF92C6F0).asColor())
                        } else if (eta.isTyphoonSchedule) {
                            Image(systemName: "hurricane")
                                .font(.system(size: 17.scaled(appContext, true)))
                                .foregroundColor(colorInt(0xFF92C6F0).asColor())
                        } else {
                            Image(systemName: "clock")
                                .font(.system(size: 17.scaled(appContext, true)))
                                .foregroundColor(colorInt(0xFF92C6F0).asColor())
                        }
                    } else {
                        let shortText = eta.firstLine.shortText
                        let text1 = shortText.first
                        let text2 = shortText.second
                        let text = text1.asAttributedString(fontSize: 17.scaled(appContext, true)) + text2.asAttributedString(fontSize: 8.scaled(appContext, true))
                        Text(text)
                            .multilineTextAlignment(.leading)
                            .lineSpacing(0)
                            .frame(alignment: .leading)
                            .foregroundColor(colorInt(0xFF92C6F0).asColor())
                            .lineLimit(1)
                    }
                }
            }
        }
        .onAppear {
            etaState.subscribe()
        }
        .onDisappear {
            etaState.unsubscribe()
        }
    }
    
}
