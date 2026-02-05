package com.jimjo.exodefender

class ScoreCalculator_OLD {

    val bonusLevelCompletion = 1450
    val bonusPerFriendly = 310
    val bonusPerEnemy = 75
    val bonusPerHealth = 375
    val bonusPerAccuracy = 975

    var friendliesSavedScore = 0
    var enemiesDestroyedScore = 0
    var skillScore = 0
    var totalScore = 0

    fun reset() {
        friendliesSavedScore = 0
        enemiesDestroyedScore = 0
        skillScore = 0
        totalScore = 0
    }

    fun calculate(flightLog: FlightLog) {
        friendliesSavedScore = flightLog.friendliesRemaining * bonusPerFriendly
        enemiesDestroyedScore = flightLog.enemiesDestroyed * bonusPerEnemy
        val healthScore = (flightLog.healthRemaining * bonusPerHealth).toInt()
        val accuracyScore = (flightLog.enemiesDestroyed / flightLog.shotsFired.toFloat() * bonusPerAccuracy). toInt()
        skillScore = healthScore + accuracyScore
        totalScore = bonusLevelCompletion + friendliesSavedScore + enemiesDestroyedScore + skillScore

//        println("bonusLevelCompletion    $bonusLevelCompletion")
//        println("friendliesSavedScore    $friendliesSavedScore")
//        println("enemiesDestroyedScore   $enemiesDestroyedScore")
//        println("  healthScore              $healthScore")
//        println("  accuracyScore            $accuracyScore")
//        println("skillScore              $skillScore")
//        println("---------------------------------------")
//        println("totalScore              $totalScore")


    }
}