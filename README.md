# منصة الصفوة الشمالية لشاحنات النقل — التحكم والتشخيص المتقدم (`qiyada-key-car-rental-1289809`)
> تكامل هندسي رفيع المستوى للتحكم الفوري بناقل البيانات والمركبات التجريبية الفاخرة

- **Project ID**: `#1289809`
- **Commercial Archetype**: `car_rental`
- **Bound Hardware Peripheral**: `OBD-II CAN-Bus Telematics + BLE Digital Key Fob`
- **Package Name**: `sa.factory.qiyadakeycarrental`
- **Zero-Emoji Invariant**: Strictly Enforced (100% Vector Icons)

---

## [Sidekick Autonomous Update] - Iteration 97

### Overview of Completed Enhancements
- **SaudiCommercialFleetBleKeyEngine**:
  - Implements Saudi Transport General Authority (TGA) Commercial Transport & Waybill Protocol compliance.
  - Driver Waybill and Saudi National ID / Iqama 10-digit authentication (Luhn modulus-10 verification).
  - Commercial Truck Electronic Immobilizer Control: Cargo bay lock/unlock, cabin lock/unlock, engine immobilizer arm/disarm.
  - Relay attack mitigation via real-time RSSI proximity thresholding (minimum -70 dBm).
  - Rotating HMAC-SHA256 authenticated frame serialization (`0x51 0x59` Magic "QY", protocol version 0x03).

- **SaudiJ1939CanBusTruckEngine**:
  - Heavy-duty commercial truck CAN-Bus protocol parser for SAE J1939 standard PGNs: EEC1 Engine RPM & Torque (PGN 61444), CCVS Wheel-based Speed (PGN 65265), ET1 Engine Coolant Temperature (PGN 65262), and DD Fuel Level (PGN 65276).
  - Saudi Ministry of Transport Highway Speed Limiter enforcing the 100 km/h commercial truck threshold.
  - Northern Province Cross-City Logistics Haversine Geofencing (Tabuk, Al-Jouf, Arar, Hail logistics hubs).
  - Axle overload and gross vehicle weight auditor against the 45-ton legal maximum limit.

- **Build Infrastructure & Clean UI Modernization**:
  - Integrated official Gradle 8.10.2 wrapper (`gradlew` and `gradle-wrapper.jar`).
  - Relocated regression tests from `main/java/test` into standard `app/src/test/java/sa/factory/qiyadakeycarrental/`.
  - Resolved Compose imports for `FontWeight` and `TextUnit.sp` in `MainActivity.kt`.
  - Fixed single-quote character literal bugs in `HomeScreen.kt` and `TelemetryScreen.kt`.
  - Replaced deprecated Material3 `Divider` with `HorizontalDivider`.
  - Purged all unicode emojis across codebase and UI screens.
  - Implemented 10 comprehensive unit tests with 100% green assertions.

---

### Step-by-Step Build and Test Instructions

1. **Clone the repository**:
   ```bash
   git clone https://github.com/hmoushim0-cloud/qiyada-key-car-rental-1289809.git
   cd qiyada-key-car-rental-1289809
   ```

2. **Run Unit Tests**:
   ```bash
   ./gradlew test
   ```

3. **Assemble Debug APK**:
   ```bash
   ./gradlew :app:assembleDebug
   ```

4. **Run on Cloud Test Harness**:
   - Triggers automatically via GitHub Actions workflow on `hmoushim0-cloud/Android-test-harness`.
   - Boots Pixel 6 emulator (API 34) and executes full diagnostic activity flow.
