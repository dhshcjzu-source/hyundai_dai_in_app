# Windows의 현재 위치(Windows 위치 서비스)를 안드로이드 에뮬레이터 GPS로 주입하는 브리지.
# Mac 전용 tools/mac_gps_bridge.swift 의 Windows 버전이다. 앱 코드/차량용 경로는 건드리지 않는다.
#
# 사용법 (PowerShell):
#   powershell -ExecutionPolicy Bypass -File tools\windows_gps_bridge.ps1 [-Serial emulator-5554] [-IntervalMs 100]
#
# 준비:
#   설정 > 개인 정보 및 보안 > 위치 > '위치 서비스' 켜기, '데스크톱 앱에서 위치에 액세스하도록 허용' 켜기.
#
# 안전장치:
#   - 'emulator-' 로 시작하는 기기에만 주입한다. 실제 차량/폰(USB, 무선 adb)은 대상에서 제외된다.
#   - 'adb emu geo fix' 는 에뮬레이터 콘솔 명령이라 실기기에는 원래 적용되지 않는다.

param(
    [string]$Serial,
    [int]$IntervalMs = 100
)

$ErrorActionPreference = 'Stop'
$inv = [System.Globalization.CultureInfo]::InvariantCulture

# --- adb 경로 ---
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } elseif ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$adb = Join-Path $sdk 'platform-tools\adb.exe'
if (-not (Test-Path $adb)) {
    Write-Host "adb를 찾을 수 없습니다: $adb (ANDROID_HOME 환경변수를 확인하세요)"
    exit 1
}

# --- 대상 에뮬레이터 선택 (실기기/차량 제외) ---
$emulators = @(& $adb devices | Select-Object -Skip 1 |
    Where-Object { $_ -match '^(emulator-\d+)\s+device\s*$' } |
    ForEach-Object { $Matches[1] })

if ($Serial) {
    if ($Serial -notlike 'emulator-*') {
        Write-Host "'$Serial' 은(는) 에뮬레이터가 아닙니다. 차량/실기기에는 주입하지 않습니다."
        exit 1
    }
    if ($emulators -notcontains $Serial) {
        Write-Host "실행 중인 에뮬레이터 중 '$Serial' 이(가) 없습니다. 현재: $($emulators -join ', ')"
        exit 1
    }
} elseif ($emulators.Count -eq 1) {
    $Serial = $emulators[0]
} elseif ($emulators.Count -eq 0) {
    Write-Host "실행 중인 에뮬레이터가 없습니다. 에뮬레이터를 먼저 켜 주세요."
    exit 1
} else {
    Write-Host "에뮬레이터가 여러 개입니다. -Serial 로 지정하세요: $($emulators -join ', ')"
    exit 1
}

# --- Windows 위치 서비스 ---
Add-Type -AssemblyName System.Device
$watcher = New-Object System.Device.Location.GeoCoordinateWatcher([System.Device.Location.GeoPositionAccuracy]::High)
$watcher.MovementThreshold = 0
$watcher.Start()

Write-Host "Windows -> 에뮬레이터 GPS 브리지 시작 (adb: $adb, 대상: $Serial, 주기: ${IntervalMs}ms)"
Write-Host "종료: Ctrl+C"

$lastKey = $null
$lastStatus = $null
try {
    while ($true) {
        if ($watcher.Permission -eq [System.Device.Location.GeoPositionPermission]::Denied) {
            Write-Host "위치 권한이 거부됨: 설정 > 개인 정보 및 보안 > 위치에서 위치 서비스와 '데스크톱 앱 액세스'를 켜 주세요."
            exit 1
        }
        if ($watcher.Status -ne $lastStatus) {
            $lastStatus = $watcher.Status
            if ($lastStatus -ne 'Ready') { Write-Host "위치 서비스 상태: $lastStatus (위치 수신 대기 중...)" }
        }

        $loc = $watcher.Position.Location
        if (-not $loc.IsUnknown) {
            $lat = $loc.Latitude.ToString('F6', $inv)
            $lon = $loc.Longitude.ToString('F6', $inv)
            $alt = if ([double]::IsNaN($loc.Altitude) -or $loc.Altitude -lt 0) { 0 } else { $loc.Altitude }
            $altStr = $alt.ToString('F1', $inv)

            # geo fix 인자 순서: 경도 위도 [고도]. 위치가 같아도 계속 재주입한다(에뮬레이터 재시작 대비).
            & $adb -s $Serial emu geo fix $lon $lat $altStr | Out-Null
            $ok = ($LASTEXITCODE -eq 0)

            $key = "$lat,$lon"
            if ($key -ne $lastKey -or -not $ok) {
                $lastKey = $key
                $acc = if ([double]::IsNaN($loc.HorizontalAccuracy)) { '?' } else { $loc.HorizontalAccuracy.ToString('F0', $inv) }
                $status = if ($ok) { '주입 완료' } else { '주입 실패' }
                Write-Host ("[{0}] Windows 위치 {1}, {2} (±{3}m) -> 에뮬레이터 {4}" -f (Get-Date -Format 'HH:mm:ss'), $lat, $lon, $acc, $status)
            }
        }
        Start-Sleep -Milliseconds $IntervalMs
    }
} finally {
    $watcher.Stop()
    $watcher.Dispose()
}
