package info.nightscout.interfaces.stats

import org.json.JSONObject

data class IsfCalculation (
    val bg : Double,
    val bgCapped : Double,
    val isfNormalTarget : Double,
    val isf : Double,
    val ratio : Double,
    val insulinDivisor: Int,
    val velocity: Double)