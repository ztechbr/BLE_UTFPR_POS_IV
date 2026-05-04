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

#include "esp_task_wdt.h"
#include "esp_system.h"
#include "Preferences.h"

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
//Inicio Alteracao
// Flags de erro dos sensores
bool tempErro = false;
bool humErro = false;
bool inaErro = false;
// Se o display OLED falhar, o ESP32 continua enviando dados por BLE/BT.
bool oledErro = false;
// fim alteracao

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
  // INICIO ALTERACAO - Não travar o ESP32 se o OLED falhar
  if (!display.begin(SSD1306_SWITCHCAPVCC, OLED_ADDRESS)) {
    Serial.println("ERRO: OLED nao encontrado. Sistema continua transmitindo.");
    oledErro = true;
  } else {
    oledErro = false;
  }
  // FIM ALTERACAO

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
  // Aumenta o MTU para enviar JSON maior por BLE
  BLEDevice::setMTU(247);

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

  // INICIO ALTERACAO - Só escreve no OLED se ele estiver funcionando
  if (!oledErro) {
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
// VERIFICA SE DISPOSITIVO I2C EXISTE
// -----------------------------------------------------------------------------
bool dispositivoI2CExiste(byte endereco) {
  Wire.beginTransmission(endereco);
  byte erro = Wire.endTransmission();

  return (erro == 0);
}

// -----------------------------------------------------------------------------
// LER SENSORES
// -----------------------------------------------------------------------------

void lerSensores() {
  TempAndHumidity data = dhtSensor.getTempAndHumidity();

  // ---------------------------
  // Diagnóstico do DHT22
  // ---------------------------
  tempErro = isnan(data.temperature);
  humErro  = isnan(data.humidity);

  if (tempErro) {
    temp = -9999;
    Serial.println("ERRO: DHT22 - Temperatura invalida");
  } else {
    temp = data.temperature;
  }

  if (humErro) {
    hum = -9999;
    Serial.println("ERRO: DHT22 - Umidade invalida");
  } else {
    hum = data.humidity;
  }

  // ---------------------------
  // Leitura e diagnóstico do INA219
  // ---------------------------

  // O INA219 normalmente usa endereço I2C 0x40.
  // Primeiro verificamos se ele responde no barramento I2C.
  bool inaPresente = dispositivoI2CExiste(0x40);

  if (!inaPresente) {
    // Sensor não respondeu no I2C: considerar falha real.
    inaErro = true;

    busVoltage = -9999;
    current_mA = -9999;
    power_mW = -9999;

    Serial.println("ERRO: INA219 nao encontrado no barramento I2C");
  } else {
    // Sensor respondeu: agora faz a leitura normal.
    busVoltage = ina219.getBusVoltage_V();
    current_mA = ina219.getCurrent_mA();
    power_mW = ina219.getPower_mW();

    // Mesmo presente, ainda validamos se a leitura veio inválida.
    inaErro = isnan(busVoltage) || isnan(current_mA) || isnan(power_mW);

    if (inaErro) {
      busVoltage = -9999;
      current_mA = -9999;
      power_mW = -9999;

      Serial.println("ERRO: INA219 - Leitura invalida");
    }
  }

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
  //inicio alteracao
  json += "\"power\":" + String(power_mW, 1) + ",";

  // Status dos sensores para diagnóstico no Android
  json += "\"tempErro\":" + String(tempErro ? 1 : 0) + ",";
  json += "\"humErro\":" + String(humErro ? 1 : 0) + ",";
  json += "\"inaErro\":" + String(inaErro ? 1 : 0) + ",";
  // Informa ao Android que o OLED está com falha
  json += "\"oledErro\":" + String(oledErro ? 1 : 0);
  // FIM ALTERACAO
  json += "}";

  Serial.print("Tamanho JSON: ");
  Serial.println(json.length());

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
  // INICIO ALTERACAO - Se OLED falhou, não tenta atualizar a tela
  // Isso evita travamentos e mantém BLE/BT funcionando.
  if (oledErro) {
    return;
  }
  // FIM ALTERACAO
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

  display.setCursor(80, 16);
  display.print("Umid:");

  display.setTextSize(2);
  display.setCursor(0, 27);

  //inicio Alteracao
  if (tempErro) {
    display.print(" ---");
  } else {
    display.print(temp, 1);
    //fim alteracao
  }
  // Simbolo grau vazado
  display.drawCircle(53, 29, 2, SSD1306_WHITE);
  display.setCursor(58, 27);
  display.print("C");
  
  display.setCursor(82, 27);

  //inicio Alteracao
  if (humErro) {
    display.print("---");
  } else {
    display.print(hum, 0);
    display.print("%");
  }
  //fim alteracao

  // Corrente e tensao
  display.setTextSize(1);

  //inicio Alteracao
  if (inaErro) {
    display.setCursor(0, 55);
    display.print("---V");

    display.setCursor(40, 55);
    display.print("---mA");

    display.setCursor(90, 55);
    display.print("---mW");
  } else {
    display.setCursor(0, 55);
    display.print(busVoltage, 2);
    display.print("V ");

    display.setCursor(40, 55);
    display.print(current_mA, 1);
    display.print("mA ");

    display.setCursor(90, 55);
    display.print(power_mW, 0);
    display.print("mW");
  }
  //fim alteracao
  display.display();
}