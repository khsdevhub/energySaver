# ByePlug  
### BLE 기반 스마트 멀티탭 제어 시스템 (Android + Arduino)

**ByePlug**는  
Android 앱과 Arduino(BLE 모듈)를 연동하여  
기존 멀티탭을 **안전하고 안정적으로 제어**하기 위한  
BLE 기반 스마트 전원 제어 프로젝트입니다.

본 프로젝트는 단순한 ON/OFF 제어를 넘어서  
**연결 끊김 대응, 자동 복구**를 핵심 목표로 설계되었습니다.

---

## 프로젝트 핵심 목표

- 앱으로 기기 제어
- 사람이 집 밖으로 나가 연결이 끊기면 **자동 전원 OFF**
- 다시 연결되면 **사용자가 설정해둔 상태로 자동 설정**

---
&nbsp;

## 전체 시스템 구성
---

# Android App (ByePlug)
[ Android App ]

└─ BLE (UART)

└─ HM-10 계열 BLE 모듈

└─ Arduino

└─ Servo Motor

└─ 멀티탭 전원 버튼
## 주요 기능

### 1. BLE 멀티탭 ON / OFF 제어
- BLE GATT UART 기반 통신
- `ON\n`, `OFF\n` 명령 전송
- ACK / 로그 수신 및 UI 반영

---

### 2. 다중 기기 관리
- 여러 BLE 멀티탭 등록 가능
- RecyclerView 기반 목록 UI
- 기기별 개별 제어

---

### 3. 기기 등록 & 편집
- 주변 BLE 기기 스캔 → 앱 내부 등록
- 중복 MAC 주소 등록 방지
- 롱클릭 메뉴:
  - 이름 변경
  - 기기 삭제

---

### 4. 기기 정보 영구 저장
- SharedPreferences + JSON
- 앱 종료 후에도 유지:
  - 등록된 기기 목록
  - 기기 이름
  - 마지막 ON/OFF 상태

---

### 5. BLE Service 기반 구조
- BLE 로직을 `Service`에서 관리
- Activity 재생성과 무관한 안정성 확보
- 다중 BLE GATT 동시 관리 가능

---

### 6. 자동 재연결
- 연결 끊김 시 점진적 재연결(backoff)
- 기기 전원 OFF → ON
- 거리 이탈 → 복귀
- 앱 재실행 시 자동 재연결

---

### 7. 블루투스 OFF → ON 자동 복구
- 시스템 Bluetooth 상태 변화 감지
- 블루투스가 다시 켜지면:
  - 등록된 모든 기기 자동 재연결

---

### 8. 재연결 시 상태 자동 동기화
- BLE `READY` 상태 수신 시
- 앱에 저장된 ON/OFF 상태를 기준으로
  - Arduino에 자동 전송
- 사용자 개입 없이 상태 복원

---

### 8. Heartbeat (PING / PONG)
- Service에서 주기적 `PING` 전송
- Arduino는 수신 시 타임아웃 리셋
- 가짜 끊김(false timeout) 최소화

---

##  Android 동작 흐름

앱 실행

└─ 저장된 기기 목록 로드

└─ BLE Service 바인드

└─ 모든 기기에 connect()
&nbsp;

BLE 연결 성공

└─ GATT 연결

└─ Service Discovery

└─ Notification 설정

└─ READY 브로드캐스트

&nbsp;
READY 수신

└─ 저장된 상태 기준 ON / OFF 동기화

&nbsp;
연결 끊김

└─ Android: 재연결 반복 시도

└─ Arduino: Fail-safe OFF

&nbsp;
재연결

└─ READY

└─ 자동 상태 복원

---

# Arduino 제어부

### 주요 기능

### 1. BLE UART 수신
- HM-10 계열 BLE 모듈 사용
- 텍스트 기반 명령 처리

---

### 2. 물리 버튼 제어
- 서보 모터로 멀티탭 전원 버튼 직접 제어
- 멀티탭 회로 개조 불필요

---


### 3. 재연결 대응
- BLE 재연결 시 정상 제어 재개
- Android 앱 상태에 따라 전원 동기화

---

### 4. 타임아웃 + Heartbeat
- 마지막 수신 시각 기준 타임아웃 판단
- `PING` 수신 시 연결 유지 연장

---

## 하드웨어 구성

| 구성 요소 | 설명 |
|---------|----|
| Arduino | UNO |
| BLE 모듈 | HM-10 계열 |
| 서보 모터 | SG90 |
| 멀티탭 | 물리 전원 버튼 포함 |

---

## 핀 연결

```text
Arduino        BLE
---------------------
D10 (RX)  <--- TXD
D11 (TX)  ---> RXD

Arduino        Servo
---------------------
D9          ---> Signal
5V          ---> VCC
GND         ---> GND
