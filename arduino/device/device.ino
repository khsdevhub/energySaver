#include <SoftwareSerial.h>
#include <Servo.h>

const int BT_RX_PIN = 10;   // HC-06 TXD → Arduino RX
const int BT_TX_PIN = 11;   // HC-06 RXD → Arduino TX

const int SERVO_PIN = 9;

SoftwareSerial BT(BT_RX_PIN, BT_TX_PIN);
Servo powerServo;

int servoRest  = 90;
int servoPress = 40;

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

  lastMessageTime = millis(); 
}

void loop() {
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

  unsigned long now = millis();
  if (isConnected && (now - lastMessageTime > DISCONNECT_TIMEOUT_MS)) {
    handleDisconnectEvent();
    isConnected = false;
  }

}

// ───────── 수신 문자열 처리 ─────────
void processLine(String line) {
  line.trim();
  line.toUpperCase();
  if (line.length() == 0) return;

  unsigned long now = millis();
  bool wasConnected = isConnected;
  isConnected = true;
  lastMessageTime = now;

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

void handleDisconnectEvent() {
  Serial.println("==== Bluetooth 연결 타임아웃 발생 ====");
  Serial.println("Fail-safe: 전원을 OFF 상태로 만들기");

  if (isPowerOn) {
    handleOffCommand();
  } else {
    //TODO: 이미 OFF 상태일 때
  }

  BT.println("DISCONNECTED");
}

void handleReconnectEvent() {
  Serial.println("==== Bluetooth 재연결 감지 ====");

  BT.print("RECONNECTED:");
  BT.println(isPowerOn ? "ON" : "OFF");
}
