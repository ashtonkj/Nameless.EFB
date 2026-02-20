package com.nameless.efb.rendering.g1000.mfd

/**
 * Fuel type with associated density used for fuel-flow unit conversion.
 *
 * South Africa default: AVGAS 0.72 kg/L, JetA-1 0.80 kg/L (SACAA convention).
 */
enum class FuelType(val densityKgPerL: Float) {
    AVGAS(0.72f),
    JET_A1(0.80f),
}

/**
 * Fuel-flow display units for the EIS strip (G-12).
 *
 * South Africa default: LPH (litres per hour) per SACAA convention.
 */
enum class FuelFlowUnit {
    /** Litres per hour — South Africa / ICAO default. */
    LPH,

    /** US gallons per hour — optional user preference. */
    GPH,
}

/**
 * Converts fuel flow from kg/s (X-Plane dataref units) to litres per hour.
 *
 * @param kgSec     Fuel flow in kilograms per second.
 * @param fuelType  Fuel type for density lookup.
 * @return Fuel flow in litres per hour.
 */
fun kgSecToLph(kgSec: Float, fuelType: FuelType): Float =
    kgSec * 3600f / fuelType.densityKgPerL

/**
 * Converts fuel flow from litres per hour to US gallons per hour.
 */
fun lphToGph(lph: Float): Float = lph / 3.785411784f

/**
 * Formats EIS (Engine Indication System) values for display on the G1000 MFD strip (G-12).
 *
 * All methods return display strings matching the G1000 CRG formatting conventions.
 */
object EisFormatter {

    /**
     * Formats fuel flow for display.
     *
     * South Africa default is LPH. When [unit] is GPH the value is converted before formatting.
     *
     * @param lph   Fuel flow in litres per hour.
     * @param unit  Display unit (LPH default for SA).
     * @return Formatted string, e.g. "8.5 LPH" or "2.2 GPH".
     */
    fun fuelFlow(lph: Float, unit: FuelFlowUnit): String = when (unit) {
        FuelFlowUnit.LPH -> "%.1f LPH".format(lph)
        FuelFlowUnit.GPH -> "%.1f GPH".format(lphToGph(lph))
    }

    /** Formats engine RPM, e.g. "2350". */
    fun rpm(rpm: Float): String = "%.0f".format(rpm)

    /** Formats manifold pressure in inHg, e.g. "22.5 inHg". */
    fun map(mapInhg: Float): String = "%.1f inHg".format(mapInhg)

    /** Formats oil temperature in degrees Celsius, e.g. "85 °C". */
    fun oilTemp(oilTempDegc: Float): String = "%.0f °C".format(oilTempDegc)

    /** Formats oil pressure in PSI, e.g. "55 PSI". */
    fun oilPress(oilPressPsi: Float): String = "%.0f PSI".format(oilPressPsi)

    /** Formats fuel quantity in kilograms, e.g. "40.5 kg". */
    fun fuelQtyKg(kg: Float): String = "%.1f kg".format(kg)

    /** Formats bus voltage, e.g. "14.2 V". */
    fun busVolts(volts: Float): String = "%.1f V".format(volts)

    /** Formats battery current, e.g. "5.2 A". */
    fun batteryAmps(amps: Float): String = "%.1f A".format(amps)
}
