/*
  ESP32 + DHT22 + OLED + INA219 + BLE + Bluetooth Classico

  DHT22: GPIO 19
  OLED/INA219 I2C: SDA 21 / SCL 22
  LED BLE: GPIO 17
  LED BT Classico: GPIO 18

  BLE Device Name: ESP32_MONITOR_BLE
  Bluetooth Classico Name: ESP32_MONITOR_BT
*/

#include <Wire.h>
#include <Adafruit_GFX.h>
#include <Adafruit_SSD1306.h>
#include "DHTesp.h"
#include <Adafruit_INA219.h>

#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>

#include "BluetoothSerial.h"

// -----------------------------------------------------------------------------
// OLED
// -----------------------------------------------------------------------------

#define SCREEN_WIDTH 128
#define SCREEN_HEIGHT 64
#define OLED_ADDRESS 0x3C

Adafruit_SSD1306 display(SCREEN_WIDTH, SCREEN_HEIGHT, &Wire, -1);

// -----------------------------------------------------------------------------
// DHT22
// -----------------------------------------------------------------------------

#define DHT_PIN 19
DHTesp dhtSensor;

// -----------------------------------------------------------------------------
// INA219
// -----------------------------------------------------------------------------

Adafruit_INA219 ina219;

// -----------------------------------------------------------------------------
// LEDS
// -----------------------------------------------------------------------------

#define LED_BLE 17
#define LED_BT  18

// -----------------------------------------------------------------------------
// BLUETOOTH CLASSICO
// -----------------------------------------------------------------------------

BluetoothSerial SerialBT;

// -----------------------------------------------------------------------------
// BLE
// -----------------------------------------------------------------------------

#define BLE_DEVICE_NAME "ESP32_MONITOR_BLE"

#define SERVICE_UUID        "12345678-1234-1234-1234-1234567890ab"
#define CHARACTERISTIC_UUID "abcd1234-1234-1234-1234-abcdef123456"

BLEServer* pServer = nullptr;
BLECharacteristic* pCharacteristic = nullptr;

bool bleConnected = false;
bool btConnected = false;

// -----------------------------------------------------------------------------
// Variáveis gerais
// -----------------------------------------------------------------------------

unsigned long lastSend = 0;
unsigned long lastBlink = 0;

bool blinkState = false;

float temp = 0;
float hum = 0;
float busVoltage = 0;
float current_mA = 0;
float power_mW = 0;

// -----------------------------------------------------------------------------
// CALLBACK BLE
// -----------------------------------------------------------------------------

class MyServerCallbacks : public BLEServerCallbacks {
  void onConnect(BLEServer* pServer) {
    bleConnected = true;
  }

  void onDisconnect(BLEServer* pServer) {
    bleConnected = false;
    BLEDevice::startAdvertising();
  }
};

// -----------------------------------------------------------------------------
// setup()
// -----------------------------------------------------------------------------

void setup() {
  Serial.begin(115200);

  pinMode(LED_BLE, OUTPUT);
  pinMode(LED_BT, OUTPUT);

  digitalWrite(LED_BLE, LOW);
  digitalWrite(LED_BT, LOW);

  Wire.begin(21, 22);

  // DHT22
  dhtSensor.setup(DHT_PIN, DHTesp::DHT22);

  // OLED
  if (!display.begin(SSD1306_SWITCHCAPVCC, OLED_ADDRESS)) {
    Serial.println("Erro ao iniciar OLED");
    while (true);
  }

  // INA219
  if (!ina219.begin()) {
    Serial.println("Erro ao iniciar INA219");
    display.clearDisplay();
    display.setCursor(0, 0);
    display.setTextColor(SSD1306_WHITE);
    display.println("Erro INA219");
    display.display();
    while (true);
  }

  // Bluetooth Classico
  SerialBT.begin("ESP32_MONITOR_BT");

  // BLE
  BLEDevice::init(BLE_DEVICE_NAME);
  BLEDevice::setMTU(100);

  pServer = BLEDevice::createServer();
  pServer->setCallbacks(new MyServerCallbacks());

  BLEService* pService = pServer->createService(SERVICE_UUID);

  pCharacteristic = pService->createCharacteristic(
    CHARACTERISTIC_UUID,
    BLECharacteristic::PROPERTY_READ |
    BLECharacteristic::PROPERTY_NOTIFY
  );

  pCharacteristic->addDescriptor(new BLE2902());

  pService->start();

  BLEAdvertising* pAdvertising = BLEDevice::getAdvertising();
  pAdvertising->addServiceUUID(SERVICE_UUID);
  pAdvertising->setScanResponse(true);
  pAdvertising->setMinPreferred(0x06);
  pAdvertising->setMaxPreferred(0x12);
  BLEDevice::startAdvertising();

  display.clearDisplay();
  display.setTextColor(SSD1306_WHITE);
  display.setTextSize(1);
  display.setCursor(0, 0);
  display.println("ESP32 Monitor");
  display.println("DHT22 + INA219");
  display.println("BLE + BT Classico");
  display.display();

  delay(2000);
}

// -----------------------------------------------------------------------------
// loop()
// -----------------------------------------------------------------------------

void loop() {
  // Pisca bolinha
  if (millis() - lastBlink >= 1000) {
    lastBlink = millis();
    blinkState = !blinkState;
  }

  // Atualiza a cada 1 segundo
  if (millis() - lastSend >= 1000) {
    lastSend = millis();

    lerSensores();
    atualizarBluetooth();
    atualizarDisplay();
    enviarDados();
  }
}

// -----------------------------------------------------------------------------
// LER SENSORES
// -----------------------------------------------------------------------------

void lerSensores() {
  TempAndHumidity data = dhtSensor.getTempAndHumidity();

  temp = data.temperature;
  hum = data.humidity;

  busVoltage = ina219.getBusVoltage_V();
  current_mA = ina219.getCurrent_mA();
  power_mW = ina219.getPower_mW();

  digitalWrite(LED_BLE, bleConnected ? HIGH : LOW);

  btConnected = SerialBT.hasClient();
  digitalWrite(LED_BT, btConnected ? HIGH : LOW);
}

// -----------------------------------------------------------------------------
// ATUALIZAR STATUS BT
// -----------------------------------------------------------------------------

void atualizarBluetooth() {
  btConnected = SerialBT.hasClient();
}

// -----------------------------------------------------------------------------
// ENVIAR DADOS BLE E BLUETOOTH CLASSICO
// -----------------------------------------------------------------------------

void enviarDados() {
  String json = "{";
  json += "\"temp\":" + String(temp, 1) + ",";
  json += "\"hum\":" + String(hum, 0) + ",";
  json += "\"voltage\":" + String(busVoltage, 2) + ",";
  json += "\"current\":" + String(current_mA, 1) + ",";
  json += "\"power\":" + String(power_mW, 1);
  json += "}";

  Serial.println(json);

  // Envia por Bluetooth Classico
  if (btConnected) {
    SerialBT.println(json);
  }

  // Envia por BLE
  if (bleConnected) {
    String jsonBle = json + "\n";
    pCharacteristic->setValue(jsonBle.c_str());
    pCharacteristic->notify();
  }
}

// -----------------------------------------------------------------------------
// DISPLAY OLED
// -----------------------------------------------------------------------------

void atualizarDisplay() {
  display.clearDisplay();
  display.setTextColor(SSD1306_WHITE);

  // Linha superior
  display.setTextSize(1);
  display.setCursor(3, 2);
  display.print("BLE:");
  display.print(bleConnected ? "ON" : "OFF");

  display.setCursor(64, 2);
  display.print("BT:");
  display.print(btConnected ? "ON" : "OFF");

  if (blinkState) {
    display.fillCircle(124, 5, 3, SSD1306_WHITE);
  } else {
    display.drawCircle(124, 5, 3, SSD1306_WHITE);
  }

  display.drawLine(0, 13, 127, 13, SSD1306_WHITE);

  // Temperatura e umidade
  display.setTextSize(1);
  display.setCursor(0, 16);
  display.print("Temp:");

  display.setCursor(70, 16);
  display.print("Umid:");

  display.setTextSize(2);
  display.setCursor(0, 27);
  display.print(temp, 1);

  // Simbolo grau vazado
  display.drawCircle(53, 29, 2, SSD1306_WHITE);
  display.setCursor(58, 27);
  display.print("C");

  display.setCursor(82, 27);
  display.print(hum, 0);
  display.print("%");

  // Corrente e tensao
  display.setTextSize(1);
  display.setCursor(0, 55);
  display.print(busVoltage, 2);
  display.print("V ");

  display.setCursor(40, 55);
  display.print(current_mA, 1);
  display.print("mA ");

  display.setCursor(90, 55);
  display.print(power_mW, 0);
  display.print("mW");

  display.display();
}