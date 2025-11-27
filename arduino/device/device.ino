#include <SoftwareSerial.h>
#include <Servo.h>

// HC-06 연결 핀
const int BT_RX_PIN = 10;   // HC-06 TXD → Arduino RX
const int BT_TX_PIN = 11;   // HC-06 RXD → Arduino TX

// 서보 핀
const int SERVO_PIN = 9;

SoftwareSerial BT(BT_RX_PIN, BT_TX_PIN);
Servo powerServo;

// 서보 각도 (멀티탭 구조에 따라 조절)
int servoRest  = 0;    // 기본 위치 (버튼 안 누름)
int servoPress = 40;   // 버튼 누를 위치 (멀티탭에 맞게 나중에 조정)

// 현재 논리 상태
bool isPowerOn = true;   // 앱에서 ON/OFF 추적용

void setup() {
  Serial.begin(9600);
  BT.begin(9600);

  powerServo.attach(SERVO_PIN);
  powerServo.write(servoRest);
  delay(500);

  Serial.println("HC-06 Smart Power Prototype Ready");
}

void loop() {
  if (BT.available()) {
    String msg = BT.readStringUntil('\n');
    msg.trim();  // 공백/개행 제거

    Serial.print("BT MSG: ");
    Serial.println(msg);

    if (msg == "ON") {
      handleOnCommand();
      BT.println("ACK ON");
    } else if (msg == "OFF") {
      handleOffCommand();
      BT.println("ACK OFF");
    } else if (msg == "PING") {
      BT.println("PONG");
    }
  }
  delay(10);
}

void handleOnCommand() {
  if (!isPowerOn) {
    Serial.println("Power -> ON (논리 상태만 변경)");
    isPowerOn = true;
    // 필요하면 ON 때 각도 변경:
    // powerServo.write(어떤각도);
  } else {
    Serial.println("이미 ON 상태");
  }
}

void handleOffCommand() {
  if (isPowerOn) {
    Serial.println("Power -> OFF, 서보로 버튼 눌러서 끄기");
    isPowerOn = false;
  } else {
    Serial.println("이미 OFF 상태지만 버튼 한 번 더 눌러줌");
  }
  pressPowerButtonOnce();
}

void pressPowerButtonOnce() {
  powerServo.write(servoPress);
  delay(400);           // 눌렀다가
  powerServo.write(servoRest);
  delay(400);           // 원위치
}
