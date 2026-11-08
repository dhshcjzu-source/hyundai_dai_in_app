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

final class Bridge: NSObject, CLLocationManagerDelegate {
    let manager = CLLocationManager()
    var last: CLLocation?

    func start() {
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyBest
        manager.distanceFilter = kCLDistanceFilterNone
        manager.requestWhenInUseAuthorization()
        manager.startUpdatingLocation()
        // 위치가 바뀌지 않아도 에뮬레이터에 주기적으로 다시 주입한다(에뮬레이터 재시작 대비).
        Timer.scheduledTimer(withTimeInterval: 2.0, repeats: true) { [weak self] _ in
            if let loc = self?.last { self?.push(loc, log: false) }
        }
    }

    func push(_ loc: CLLocation, log: Bool) {
        let lat = loc.coordinate.latitude, lon = loc.coordinate.longitude
        // geo fix 인자 순서: 경도 위도 [고도]
        let status = adb(["emu", "geo", "fix", String(lon), String(lat), String(max(loc.altitude, 0))])
        if log {
            print(String(format: "[%@] Mac 위치 %.6f, %.6f (±%.0fm) → 에뮬레이터 %@",
                         DateFormatter.localizedString(from: Date(), dateStyle: .none, timeStyle: .medium),
                         lat, lon, loc.horizontalAccuracy, status == 0 ? "주입 완료" : "주입 실패"))
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
