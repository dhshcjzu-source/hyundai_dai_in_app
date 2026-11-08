// Mac의 실제 현재 위치(CoreLocation)를 안드로이드 에뮬레이터 GPS로 주입하는 브리지.
// 사용법: swift tools/mac_gps_bridge.swift [emulator-serial]
//   예) swift tools/mac_gps_bridge.swift emulator-5554
// 처음 실행 시 macOS가 터미널(또는 실행한 앱)의 위치 접근 권한을 묻는다 → 허용.
// 시스템 설정 > 개인정보 보호 및 보안 > 위치 서비스가 켜져 있어야 한다.

import CoreLocation
import Foundation

setvbuf(stdout, nil, _IONBF, 0)

let serial = CommandLine.arguments.count > 1 ? CommandLine.arguments[1] : nil
let adbPath: String = {
    if let sdk = ProcessInfo.processInfo.environment["ANDROID_HOME"] {
        return "\(sdk)/platform-tools/adb"
    }
    return "\(NSHomeDirectory())/Library/Android/sdk/platform-tools/adb"
}()

@discardableResult
func adb(_ args: [String]) -> Int32 {
    let p = Process()
    p.executableURL = URL(fileURLWithPath: adbPath)
    p.arguments = (serial.map { ["-s", $0] } ?? []) + args
    p.standardOutput = FileHandle.nullDevice
    p.standardError = FileHandle.standardError
    do { try p.run(); p.waitUntilExit(); return p.terminationStatus } catch {
        FileHandle.standardError.write("adb 실행 실패: \(error)\n".data(using: .utf8)!)
        return -1
    }
}

/// 에뮬레이터 GPS 재주입 주기 (0.1초).
let pushInterval: TimeInterval = 0.1

final class Bridge: NSObject, CLLocationManagerDelegate {
    let manager = CLLocationManager()
    var last: CLLocation?
    // adb 호출은 백그라운드 직렬 큐에서 한다. 이전 호출이 안 끝났으면 이번 주기는 건너뛰어 밀리지 않게 한다.
    let pushQueue = DispatchQueue(label: "gps.bridge.push")
    var pushing = false

    func start() {
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyBest
        manager.distanceFilter = kCLDistanceFilterNone
        manager.requestWhenInUseAuthorization()
        manager.startUpdatingLocation()
        // 0.1초마다 최신 위치를 에뮬레이터에 다시 주입한다(앱 0.1초 갱신과 맞춤, 에뮬레이터 재시작 대비).
        let timer = Timer(timeInterval: pushInterval, repeats: true) { [weak self] _ in
            if let loc = self?.last { self?.push(loc, log: false) }
        }
        timer.tolerance = 0.01
        RunLoop.main.add(timer, forMode: .common)
    }

    func push(_ loc: CLLocation, log: Bool) {
        if pushing && !log { return }
        pushing = true
        let lat = loc.coordinate.latitude, lon = loc.coordinate.longitude
        let alt = max(loc.altitude, 0), accuracy = loc.horizontalAccuracy
        pushQueue.async { [weak self] in
            // geo fix 인자 순서: 경도 위도 [고도]
            let status = adb(["emu", "geo", "fix", String(lon), String(lat), String(alt)])
            DispatchQueue.main.async {
                self?.pushing = false
                if log {
                    print(String(format: "[%@] Mac 위치 %.6f, %.6f (±%.0fm) → 에뮬레이터 %@",
                                 DateFormatter.localizedString(from: Date(), dateStyle: .none, timeStyle: .medium),
                                 lat, lon, accuracy, status == 0 ? "주입 완료" : "주입 실패"))
                }
            }
        }
    }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let loc = locations.last else { return }
        last = loc
        push(loc, log: true)
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        print("위치 수신 실패: \(error.localizedDescription)")
    }

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        switch manager.authorizationStatus {
        case .denied, .restricted:
            print("위치 권한이 거부됨: 시스템 설정 > 개인정보 보호 및 보안 > 위치 서비스에서 터미널을 허용하세요.")
        case .notDetermined:
            print("위치 권한 요청 중...")
        default:
            manager.startUpdatingLocation()
        }
    }
}

print("Mac → 에뮬레이터 GPS 브리지 시작 (adb: \(adbPath)\(serial.map { ", 대상: \($0)" } ?? ""))")
let bridge = Bridge()
bridge.start()
RunLoop.main.run()
