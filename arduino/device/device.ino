#include <SoftwareSerial.h>
#include <Servo.h>

// HC-06 연결 핀
const int BT_RX_PIN = 10;   // HC-06 TXD → Arduino RX
const int BT_TX_PIN = 11;   // HC-06 RXD → Arduino TX

// 서보 핀
const int SERVO_PIN = 9;

SoftwareSerial BT(BT_RX_PIN, BT_TX_PIN);
Servo powerServo;

// 서보 위치 (필요에 따라 조정)
int servoRest  = 90;
int servoPress = 40;

// 앱에서 ON/OFF 추적용 (초기 전원 상태에 따라 true/false 조절)
bool isPowerOn = true;

// 연결 상태 / 타임아웃 관련
bool isConnected = false;                     // 최근에 메시지 받은 적 있는지
unsigned long lastMessageTime = 0;           // 마지막으로 유효 명령 받은 시각
const unsigned long DISCONNECT_TIMEOUT_MS = 10000; // 5초 동안 아무 메시지 없으면 끊긴 걸로 판단

// 수신 버퍼
String rxBuffer = "";

// ───────── 함수 선언 ─────────
void handleOnCommand();
void handleOffCommand();
void pressPowerButtonOnce(int servoRest, int servoPress);
void handleDisconnectEvent();
void handleReconnectEvent();
void processLine(String line);

void setup() {
  Serial.begin(9600);
  BT.begin(9600);

  powerServo.attach(SERVO_PIN);
  powerServo.write(servoRest);
  delay(500);

  Serial.println("HC-06 Smart Power Prototype Ready");
  Serial.println("명령: ON / OFF / PING (/ STATUS 등 확장 가능)");

  lastMessageTime = millis();   // 타임아웃 기준 시작점
}

void loop() {
  // 1) 블루투스 수신 처리 (논블로킹 파싱)
  while (BT.available()) {
    char c = BT.read();

    if (c == '\n' || c == '\r') {
      if (rxBuffer.length() > 0) {
        processLine(rxBuffer);
        rxBuffer = "";
      }
    } else {
      rxBuffer += c;
    }
  }

  // 2) 연결 타임아웃 체크 (끊김 이벤트)
  unsigned long now = millis();
  if (isConnected && (now - lastMessageTime > DISCONNECT_TIMEOUT_MS)) {
    // 이 시점에서 "실질적인 연결 끊김"으로 판단
    handleDisconnectEvent();
    isConnected = false;
  }

  // 3) 나머지 반복 작업이 있으면 여기에...
}

// ───────── 수신 문자열 처리 ─────────
void processLine(String line) {
  line.trim();       // 공백/줄바꿈 제거
  line.toUpperCase(); // 소문자 → 대문자

  if (line.length() == 0) return;

  // 메시지 도착 → 연결 살아있다고 보고 시간 갱신
  unsigned long now = millis();
  bool wasConnected = isConnected;
  isConnected = true;
  lastMessageTime = now;

  // 기존엔 끊어졌다고 보고 있었는데, 다시 메시지를 받았다면 = 재연결
  if (!wasConnected) {
    handleReconnectEvent();
  }

  Serial.print("BT MSG: ");
  Serial.println(line);

  if (line == "ON") {
    handleOnCommand();
    BT.println("ACK ON");
  } else if (line == "OFF") {
    handleOffCommand();
    BT.println("ACK OFF");
  } else if (line == "PING") {
    BT.println("PONG");
  } else if (line == "STATUS") {
    // 상태 요청이 오면 현재 상태를 돌려줄 수도 있음 (선택)
    BT.println(isPowerOn ? "ON" : "OFF");
  } else {
    Serial.println("알 수 없는 명령");
  }
}

// ───────── ON / OFF 명령 처리 ─────────
void handleOnCommand() {
  if (!isPowerOn) {
    Serial.println("Power -> ON (논리 상태만 변경)");
    isPowerOn = true;
  } else {
    Serial.println("이미 ON 상태");
  }

  // 네가 기존에 쓰던 방향 유지
  pressPowerButtonOnce(servoRest, -servoPress);
}

void handleOffCommand() {
  if (isPowerOn) {
    Serial.println("Power -> OFF, 서보로 버튼 눌러서 끄기");
    isPowerOn = false;
  } else {
    Serial.println("이미 OFF 상태지만 버튼 한 번 더 눌러줌");
  }

  pressPowerButtonOnce(servoRest, servoPress);
}

// ───────── 서보로 버튼 한 번 누르기 ─────────
void pressPowerButtonOnce(int servoRest, int servoPress) {
  powerServo.write(servoRest + servoPress);
  delay(400);
  powerServo.write(servoRest);
  delay(400);
}

// ───────── 연결 끊김/재연결 이벤트 처리 ─────────

// DISCONNECT_TIMEOUT_MS 동안 아무 메시지도 없으면 호출
void handleDisconnectEvent() {
  Serial.println("==== Bluetooth 연결 타임아웃 발생 ====");
  Serial.println("Fail-safe: 전원을 OFF 상태로 만들기");

  // 안전을 위해 항상 OFF 상태로 만들어 두기
  if (isPowerOn) {
    handleOffCommand();
  } else {
    // 이미 OFF라면 굳이 버튼 안 눌러도 됨
    // 필요하면 강제 OFF 보장을 위해 한 번 더 누를 수도 있음
    // pressPowerButtonOnce(servoRest, servoPress);
  }

  // 끊겼다는 표시를 앱으로 보내고 싶으면(연결이 실제로 끊겼으면 안 갈 수 있음)
  BT.println("DISCONNECTED");
}

// 새 메시지를 받으면서 "다시 연결됨"으로 판단될 때 한 번 호출
void handleReconnectEvent() {
  Serial.println("==== Bluetooth 재연결 감지 ====");

  // 현재 상태를 앱에 알려주고 싶을 때
  BT.print("RECONNECTED:");
  BT.println(isPowerOn ? "ON" : "OFF");
}
