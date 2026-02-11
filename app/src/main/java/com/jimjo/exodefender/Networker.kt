package com.jimjo.exodefender

import android.os.Handler
import android.os.Looper
import android.os.Message
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.net.URL
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.time.Instant
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSession
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.random.Random
import kotlinx.serialization.*
import kotlinx.serialization.json.*
import java.net.HttpURLConnection

class LLPOVHostnameVerifier: HostnameVerifier {
    override fun verify(p0: String?, p1: SSLSession?): Boolean {
        // accept any hostname
        return true
    }
}

enum class NetworkResponse(val value: Int) {

    // success codes
    TEST_OK(0),
    CREATE_USER(1),
    UPDATE_USER(2),
    GET_ACTIVITY_FLIGHT_LOG(8),
    GET_CALLSIGN(9),
    UPDATE_ENVIRONMENT(10),
    GET_GLOBAL_SETTINGS(14),
    UPSERT_LEVELS(17),
    GET_LEVELS_MANIFEST(18),
    GET_LEVELS(19),
    GET_SYNC_MANIFEST(20),
    SUBMIT_MISSION_SCORE(21),
    GET_MISSION_RANKINGS(22),
    UPLOAD_FLIGHT_LOG(23),
    DOWNLOAD_FLIGHT_LOG(24),



    // Error codes:
//     -1 logic error
//     -2 server code error
//     -3 HTTP error
//     -4 client-side error
}


enum class FlightRecordPurpose(val value: Int) {
    UNKNOWN(0), LEADERBOARD(1), REPLAYS(2), ADMIN(3)
}

class Networker(receiver: NetworkResponseReceiver? = null, var hostServer: String) {



    @Serializable
    data class GetCallsignResponse(val success: Boolean, val userIdExists: Boolean, val callSign: String)

    @Serializable
    data class UpdateUserResponse(val success: Boolean, val userId: Int, val callSignInUse: Boolean, val serverError: Boolean)

    @Serializable
    data class UploadFlightLogRequest(val userId: Int, val levelId: Int, val flightLogId: Int, val flightLog: String, val padding: String)

    @Serializable
    data class DownFlightLogResponse(val userId: Int, val levelId: Int, val flightLogId: Int, val flightLog: String)

    @Serializable
    data class ActivityFlightLogResponse(val userId: Int, val callSign: String, val flightLog: String)

    @Serializable
    data class LogActivityRequest(val userId: Int, val code: Int, val levelId: Int, val data: String, val flightLog: String, val padding: String)





    // EXO classes

    @Serializable
    data class TestConnectionResponse(val exoServerVersion: String, val database: String, val dbConnection: Boolean)

    @Serializable
    data class GlobalSetting(val key: String, val value: String)

    @Serializable
    data class GlobalSettingsResponse(val success: Boolean, val settings: List<GlobalSetting>)

    @Serializable
    data class CreateUserRequest(val installId: String, val padding: String)
    @Serializable
    data class CreateUserResponse(val success: Boolean, val userId: Int, val callSign: String)

    // Represents what the Worker needs to decide retry/fail.
    sealed class CreateUserResult {
        data class Ok(val response: CreateUserResponse) : CreateUserResult()
        data class HttpError(val code: Int, val body: String?) : CreateUserResult()
    }

    @Serializable
    data class UpsertLevelsRequest(val levels: List<String>, val spacing: Int, val padding: String)

    @Serializable
    data class UpsertLevelsResponse(val success: Boolean, val insertCount: Int, val updateCount: Int, val failureCount: Int)

    @Serializable
    data class LevelManifestItem(val id: Int, val version: Int, val isDeleted: Boolean)

    @Serializable
    data class CampaignManifestItem(val code: String, val name: String, val isDeleted: Boolean)

    @Serializable
    data class SyncManifestResponse(val success: Boolean, val levels: List<LevelManifestItem> = emptyList(), val campaigns: List<CampaignManifestItem> = emptyList())

    @Serializable
    @JsonIgnoreUnknownKeys
    data class LevelSerializable(val id: Int, val type: String, val json: String, val version: Int, val updatedAt: String)

    @Serializable
    data class LevelsResponse(val success: Boolean, val levels: List<LevelSerializable>)


    @Serializable
    data class ScoreSubmitRequest(
        val userId: Int,
        val levelId: Int,
        val runId: String,
        val clientVersionCode: Int,
        val objectiveType: Int,
        val completionOutcome: Int,
        val scoreVersion: Int,
        val scoreTotal: Int,
        val details: String? = null,
        val highlights: String? = null,
        val padding: String
    )


    @Serializable
    data class ScoreSubmitResult(
        val accepted: Boolean,
        val duplicateRun: Boolean = false,
        val rejectReason: String? = null,
        val submissionId: Long? = null,
        val newPersonalBest: Boolean = false,
        val bestScoreTotal: Int? = null
    )

    @Serializable
    data class MissionRankingsResponse(
        @SerialName("ok") val ok: Boolean,
        @SerialName("rejectReason") val rejectReason: String? = null,
        @SerialName("levelId") val levelId: Int,
        @SerialName("myRank") val myRank: Int? = null,
        @SerialName("totalPlayers") val totalPlayers: Int = 0,
        @SerialName("topPercent") val topPercent: Int? = null,
        @SerialName("local") val local: List<MissionRankingRow> = emptyList(),
        @SerialName("top") val top: List<MissionRankingRow> = emptyList(),
    )

    @Serializable
    data class MissionRankingRow(
        @SerialName("rankNo") val rankNo: Int,
        @SerialName("callsign") val callsign: String,
        @SerialName("scoreTotal") val scoreTotal: Int,
        @SerialName("achievedAt") val achievedAt: String,
        @SerialName("isMe") val isMe: Boolean
    )


    private var connection: HttpURLConnection? = null
    private val handler: NetworkHandler? =
        receiver?.let { NetworkHandler(Looper.getMainLooper(), it) }
//    private var hostServer = "http://52.249.217.188"
//    private var hostServer = "http://52.249.217.188:8080"
//    private var hostServer = "http://192.168.0.15:7139"

    private val scoresPath = "api/scores"
    private val userPath = "api/user"
    private val systemPath = "api/system"
    val networkTimeout = 7000

    fun makePadding(value: String): String {
        var numeric = 0
        for (c in value) {
            numeric +=c.code
        }
        return makePadding(numeric)
    }

    fun makePadding(value: Int): String {
        val instant = Instant.now()
        val intervals = instant.epochSecond / 300 // number of 10 minute intervals since epoch
        // make key
        val key = intervals + value
        // reverse string representation of key and append two random digits
        val padding = key.toString().reversed() + (Random.nextInt(89) + 10).toString()

        return padding
    }

    fun testConn() {
        try {
//            println("Connecting to " + hostServer)

            val url = "$hostServer/$systemPath/test"
            connection = URL(url).openConnection() as HttpURLConnection
            connection!!.connectTimeout = networkTimeout

            connection!!.setRequestProperty("Content-Type", "application/json")
            connection!!.setRequestProperty("accept", "*/*")

            val responseCode = connection!!.getResponseCode()

            if (responseCode == HttpURLConnection.HTTP_OK) {
                val data = connection!!.inputStream.bufferedReader().readText()
                val testConnectionResponse = Json.decodeFromString(TestConnectionResponse.serializer(), data)
                handler?.let { it.sendMessage(it.obtainMessage(NetworkResponse.TEST_OK.value, testConnectionResponse)) }

            } else {
                handler?.let { it.sendMessage(it.obtainMessage(-3, "HTTP Code $responseCode (${url})")) }
            }
        }
        catch(e: Exception) {
            handler?.let { it.sendMessage(it.obtainMessage(-4, if (e.message != null) e.message else e::class.simpleName)) }
        }
        finally {
            connection?.disconnect()
        }
    }

    fun upsertLevels(levels: List<Level>) {
        try {

            val url = "$hostServer/$systemPath/levels"
            connection = URL(url).openConnection() as HttpURLConnection
            connection!!.connectTimeout = networkTimeout
            connection!!.doOutput = true

            // the client must generate a padding based on the userid that the server will validate
            val spacing = Random.nextInt(90000) + 10000
            val padding = makePadding(spacing)

            val levelsJson = mutableListOf<String>()
            for (level in levels) {
                levelsJson.add(level.getLevelSerializable().stringify())
            }

            val postBody = Json.encodeToString(UpsertLevelsRequest(levelsJson, spacing, padding))

            connection!!.setRequestProperty("Content-Type", "application/json")
            connection!!.setRequestProperty("accept", "*/*")

            connection!!.connect()

            val outStream = OutputStreamWriter(connection!!.getOutputStream())
            val bufferedWriter = BufferedWriter(outStream)
            bufferedWriter.write(postBody)
            bufferedWriter.flush()
            bufferedWriter.close()

            val responseCode = connection!!.getResponseCode()

            if (responseCode == HttpURLConnection.HTTP_OK) {
                val data = connection!!.inputStream.bufferedReader().readText()
                val upsertLevelsResponse = Json.decodeFromString(UpsertLevelsResponse.serializer(), data)
                if (upsertLevelsResponse.success) {
                    handler?.let { it.sendMessage(it.obtainMessage(NetworkResponse.UPSERT_LEVELS.value, upsertLevelsResponse)) }
                }
                else {
                    handler?.let { it.sendMessage(it.obtainMessage(-2, "A server error occured while upserting levels")) }
                }
            }
            else {
                handler?.let { it.sendMessage(it.obtainMessage(-3, "HTTP Code $responseCode (${url})")) }
            }

        }
        catch(e: Exception) {
//            println("Network Error: ${e.message}")
            handler?.let { it.sendMessage(it.obtainMessage(-4, if (e.message != null) e.message else e::class.simpleName)) }
        }
        finally {
            connection?.disconnect()
        }
    }

    fun getSyncManifest(includeDevelopment: Boolean) {
        try {
            val url = "$hostServer/$systemPath/sync/manifest?includedev=$includeDevelopment"
            connection = URL(url).openConnection() as HttpURLConnection
            connection!!.connectTimeout = networkTimeout

            val responseCode = connection!!.responseCode

            if (responseCode == HttpURLConnection.HTTP_OK) {
                val data = connection!!.inputStream.bufferedReader().readText()

                val syncManifest = Json.decodeFromString(SyncManifestResponse.serializer(), data)

                if (syncManifest.success) {
                    handler?.let {
                        it.sendMessage(
                            it.obtainMessage(
                                NetworkResponse.GET_SYNC_MANIFEST.value,
                                syncManifest
                            )
                        )
                    }
                } else {
                    handler?.let {
                        it.sendMessage(
                            it.obtainMessage(-2, "A server error occurred while requesting sync manifest")
                        )
                    }
                }
            } else {
                handler?.let {
                    it.sendMessage(
                        it.obtainMessage(-3, "HTTP Code $responseCode ($url)")
                    )
                }
            }
        } catch (e: Exception) {
            handler?.let {
                it.sendMessage(
                    it.obtainMessage(-4, e.message ?: e::class.simpleName)
                )
            }
        } finally {
            connection?.disconnect()
        }
    }

    fun getLevels(ids: List<Int>) {

        try {
            val url = "$hostServer/$systemPath/levels?ids=" + ids.joinToString(",")
            connection = URL(url).openConnection() as HttpURLConnection
            connection!!.connectTimeout = networkTimeout

            val responseCode = connection!!.getResponseCode()

            if (responseCode == HttpURLConnection.HTTP_OK) {
                val data = connection!!.inputStream.bufferedReader().readText()
                val levelsResponse = Json.decodeFromString(LevelsResponse.serializer(), data)
                if (levelsResponse.success) {
                    handler?.let { it.sendMessage(it.obtainMessage(NetworkResponse.GET_LEVELS.value, levelsResponse)) }
                } else {
                    handler?.let { it.sendMessage(it.obtainMessage(-2, "A server error occurred while requesting levels")) }
                }
            } else {
                handler?.let { it.sendMessage(it.obtainMessage(-3, "HTTP Code $responseCode (${url})")) }
            }
        } catch (e: Exception) {
            handler?.let { it.sendMessage(it.obtainMessage(-4, if (e.message != null) e.message else e::class.simpleName)) }
        } finally {
            connection?.disconnect()
        }
    }



    fun getCallsign(userId: Int) {
        try {
            val url = "$hostServer/$userPath/$userId"
            connection = URL(url).openConnection() as HttpURLConnection
            connection!!.connectTimeout = networkTimeout

            val responseCode = connection!!.getResponseCode()

            if (responseCode == HttpURLConnection.HTTP_OK) {
                val data = connection!!.inputStream.bufferedReader().readText()
                val getCallsignResponse = Json.decodeFromString(GetCallsignResponse.serializer(), data)
                if(getCallsignResponse.success) {
                    if (getCallsignResponse.userIdExists) {
                        handler?.let { it.sendMessage(it.obtainMessage(NetworkResponse.GET_CALLSIGN.value, getCallsignResponse.callSign)) }
                    }
                    else {
                        handler?.let { it.sendMessage(it.obtainMessage(-1, "userId $userId does not exist")) }
                    }
                }
                else {
                    handler?.let { it.sendMessage(it.obtainMessage(-2, "A server error occurred while looking up callsign")) }
                }
            } else {
                handler?.let { it.sendMessage(it.obtainMessage(-3, "HTTP Code $responseCode (${url})")) }
            }
        }
        catch(e: Exception) {
            handler?.let { it.sendMessage(it.obtainMessage(-4, e.message)) }
        }
        finally {
            connection?.disconnect()
        }
    }


    fun getGlobalSettings() {
        try {

            val url = "$hostServer/$systemPath/settings"
            connection = URL(url).openConnection() as HttpURLConnection
            connection!!.connectTimeout = networkTimeout

            val responseCode = connection!!.getResponseCode()

            if (responseCode == HttpURLConnection.HTTP_OK) {
                val data = connection!!.inputStream.bufferedReader().readText()
                val response = Json.decodeFromString(GlobalSettingsResponse.serializer(), data)
                if(response.success) {
                    handler?.let { it.sendMessage(it.obtainMessage(NetworkResponse.GET_GLOBAL_SETTINGS.value, response.settings)) }
                }
                else {
                    handler?.let { it.sendMessage(it.obtainMessage(-2, "A server error occurred while retrieving global settings")) }
                }
            } else {
                handler?.let { it.sendMessage(it.obtainMessage(-3, "HTTP Code $responseCode (${url})")) }
            }
        }
        catch(e: Exception) {
            handler?.let { it.sendMessage(it.obtainMessage(-4, e.message)) }
        }
        finally {
            connection?.disconnect()
        }
    }

    fun createNewUserForWorker(installId: String): CreateUserResult {
        var connection: HttpURLConnection? = null

        try {
            val url = "$hostServer/$userPath"
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = networkTimeout
                readTimeout = networkTimeout
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "*/*")
            }

            val padding = makePadding(installId)
            val postBody = Json.encodeToString(CreateUserRequest(installId, padding))

            connection.outputStream.use { os ->
                BufferedWriter(OutputStreamWriter(os, Charsets.UTF_8)).use { w ->
                    w.write(postBody)
                    w.flush()
                }
            }

            val code = connection.responseCode

            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }

            return if (code == HttpURLConnection.HTTP_OK && body != null) {
                val response = Json.decodeFromString(CreateUserResponse.serializer(), body)
                CreateUserResult.Ok(response)
            } else {
                CreateUserResult.HttpError(code, body)
            }

        } finally {
            connection?.disconnect()
        }
    }
    fun updateEnvironment(userId: Int, versionCode: Long, platform: String) {
        try {

            val url = "$hostServer/$userPath/env/$userId"
            connection = URL(url).openConnection() as HttpURLConnection
            connection!!.requestMethod = "PUT"
            connection!!.connectTimeout = networkTimeout
            connection!!.doOutput = true

            val putBody = "{\"versionCode\":\"$versionCode\",\"platform\":\"$platform\"}"

            connection!!.setRequestProperty("Content-Type", "application/json")
            connection!!.setRequestProperty("accept", "*/*")

            connection!!.connect()

            val outStream = OutputStreamWriter(connection!!.getOutputStream())
            val bufferedWriter = BufferedWriter(outStream)
            bufferedWriter.write(putBody)
            bufferedWriter.flush()
            bufferedWriter.close()

            val responseCode = connection!!.getResponseCode()

            if (responseCode == HttpURLConnection.HTTP_OK) {
                val data = connection!!.inputStream.bufferedReader().readText()
                val updateResponse = data.toBoolean()
                if (updateResponse) {
                    handler?.let { it.sendMessage(it.obtainMessage(NetworkResponse.UPDATE_ENVIRONMENT.value, "Environment successfully updated for '$userId'")) }
                }
                else {
                    handler?.let { it.sendMessage(it.obtainMessage(-2, "A server error occured while creating or updating user")) }
                }
            }
            else {
                handler?.let { it.sendMessage(it.obtainMessage(-3, "HTTP Code $responseCode (${url})")) }
            }
        }
        catch(e: Exception) {
            handler?.let { it.sendMessage(it.obtainMessage(-4, e.message)) }
        }
        finally {
            connection?.disconnect()
        }
    }


    fun updateUser(userId: Int, callSign: String?) {
        try {

            val url = "$hostServer/$userPath/$userId"
            connection = URL(url).openConnection() as HttpURLConnection
            connection!!.requestMethod = "PUT"
            connection!!.connectTimeout = networkTimeout
            connection!!.doOutput = true

            // the client must generate a padding based on the userid that the server will validate
            val callSignValue: String
            if (callSign != null) {
                callSignValue = callSign
            }
            else {
                callSignValue = ""
            }
            val padding = makePadding(callSignValue)
            val putBody = "{\"callSign\":\"$callSign\",\"padding\":\"$padding\"}"

            connection!!.setRequestProperty("Content-Type", "application/json")
            connection!!.setRequestProperty("accept", "*/*")

            connection!!.connect()

            val outStream = OutputStreamWriter(connection!!.getOutputStream())
            val bufferedWriter = BufferedWriter(outStream)
            bufferedWriter.write(putBody)
            bufferedWriter.flush()
            bufferedWriter.close()

            val responseCode = connection!!.getResponseCode()

            if (responseCode == HttpURLConnection.HTTP_OK) {
                val data = connection!!.inputStream.bufferedReader().readText()
                val updateUserResponse = Json.decodeFromString(UpdateUserResponse.serializer(), data)
                if (updateUserResponse.success) {
                    handler?.let { it.sendMessage(it.obtainMessage(NetworkResponse.UPDATE_USER.value, "Callsign successfully updated to '$callSign'")) }
                }
                else if (updateUserResponse.callSignInUse) {
                    handler?.let { it.sendMessage(it.obtainMessage(-1, "Callsign '$callSign' in use, user not updated")) }
                }
                else if (updateUserResponse.serverError) {
                    handler?.let { it.sendMessage(it.obtainMessage(-2, "A server error occured while creating or updating user")) }
                }
            }
            else {
                handler?.let { it.sendMessage(it.obtainMessage(-3, "HTTP Code $responseCode (${url})")) }
            }

        }
        catch(e: Exception) {
            handler?.let { it.sendMessage(it.obtainMessage(-4, e.message)) }
        }
        finally {
            connection?.disconnect()
        }
    }

    /**
     * Submit a mission score to the server.
     *
     * Caller provides details/highlights JSON (or null).
     */
    fun submitMissionScore(
        userId: Int,
        levelId: Int,
        runId: String,                  // UUID string generated client-side per run
        clientVersionCode: Int,
        objectiveType: Int,             // 0..4
        completionOutcome: Int,         // SUCCESS=1 etc
        scoreVersion: Int,
        scoreTotal: Int,
        details: String? = null,
        highlights: String? = null
    ) {
        try {
            val url = "$hostServer/$scoresPath/mission/submit"
            connection = URL(url).openConnection() as HttpURLConnection
            connection!!.connectTimeout = networkTimeout
            connection!!.readTimeout = networkTimeout
            connection!!.doOutput = true
            connection!!.requestMethod = "POST"

            // the client must generate a padding based on the runId that the server will validate
            val padding = makePadding(runId)

            val bodyObj = ScoreSubmitRequest(
                userId = userId,
                levelId = levelId,
                runId = runId,
                clientVersionCode = clientVersionCode,
                objectiveType = objectiveType,
                completionOutcome = completionOutcome,
                scoreVersion = scoreVersion,
                scoreTotal = scoreTotal,
                details = details,
                highlights = highlights,
                padding = padding
            )

            val putBody = Json.encodeToString(ScoreSubmitRequest.serializer(), bodyObj)

            connection!!.setRequestProperty("Content-Type", "application/json")
            connection!!.setRequestProperty("accept", "*/*")

            connection!!.connect()

            BufferedWriter(OutputStreamWriter(connection!!.outputStream)).use { w ->
                w.write(putBody)
                w.flush()
            }

            val responseCode = connection!!.responseCode

            fun postResultToUi(res: ScoreSubmitResult) {
                handler?.let {
                    it.sendMessage(it.obtainMessage(NetworkResponse.SUBMIT_MISSION_SCORE.value, res))
                }
            }

            if (responseCode == HttpURLConnection.HTTP_OK) {
                val dataOut = connection!!.inputStream.bufferedReader().readText()
                val res = Json.decodeFromString(ScoreSubmitResult.serializer(), dataOut)
                postResultToUi(res)
            } else if (
                responseCode == HttpURLConnection.HTTP_BAD_REQUEST ||
                responseCode == HttpURLConnection.HTTP_NOT_FOUND ||
                responseCode == HttpURLConnection.HTTP_INTERNAL_ERROR
            ) {
                // Server likely returned ScoreSubmitResult in body; try to parse it.
                val errText = try { connection!!.errorStream?.bufferedReader()?.readText() } catch (_: Exception) { null }

                if (!errText.isNullOrBlank()) {
                    try {
                        val res = Json.decodeFromString(ScoreSubmitResult.serializer(), errText)
                        postResultToUi(res)
                    } catch (_: Exception) {
                        handler?.let { it.sendMessage(it.obtainMessage(-3, "HTTP Code $responseCode ($url)")) }
                    }
                } else {
                    handler?.let { it.sendMessage(it.obtainMessage(-3, "HTTP Code $responseCode ($url)")) }
                }
            } else {
                handler?.let { it.sendMessage(it.obtainMessage(-3, "HTTP Code $responseCode ($url)")) }
            }
        } catch (e: Exception) {
            handler?.let { it.sendMessage(it.obtainMessage(-4, e.message)) }
        } finally {
            connection?.disconnect()
        }
    }

    fun getMissionRankings(levelId: Int, userId: Int, window: Int = 4, topLimit: Int = 10) {

        try {
            val url = "$hostServer/$scoresPath/mission/$levelId/rankings" +
                    "?userId=$userId&window=$window&topLimit=$topLimit"

            connection = URL(url).openConnection() as HttpURLConnection
            connection!!.requestMethod = "GET"
            connection!!.connectTimeout = networkTimeout
            connection!!.readTimeout = networkTimeout
            connection!!.setRequestProperty("Accept", "application/json")

            val responseCode = connection!!.responseCode

            if (responseCode == HttpURLConnection.HTTP_OK) {
                val data = connection!!.inputStream.bufferedReader().readText()
                val dto = Json { ignoreUnknownKeys = true; explicitNulls = false }
                    .decodeFromString(MissionRankingsResponse.serializer(), data)

                if (dto.ok) {
                    handler?.let {
                        it.sendMessage(it.obtainMessage(NetworkResponse.GET_MISSION_RANKINGS.value, dto))
                    }
                } else {
                    handler?.let {
                        it.sendMessage(
                            it.obtainMessage(
                                -2,
                                dto.rejectReason ?: "Rankings request rejected (ok=false)"
                            )
                        )
                    }
                }
            } else {
                // Helpful: read error body if present
                val err = connection!!.errorStream?.bufferedReader()?.readText()
                val msg = if (!err.isNullOrBlank())
                    "HTTP Code $responseCode ($url)\n${err.take(500)}"
                else
                    "HTTP Code $responseCode ($url)"

                handler?.let { it.sendMessage(it.obtainMessage(-3, msg)) }
            }
        } catch (e: Exception) {
            handler?.let {
                it.sendMessage(
                    it.obtainMessage(
                        -4,
                        e.message ?: e::class.simpleName ?: "Exception"
                    )
                )
            }
        } finally {
            connection?.disconnect()
        }
    }


    fun getActivityFlightLog(id: Int) {
        try {

            val url = "$hostServer/$userPath/activityflightlog/$id"
            connection = URL(url).openConnection() as HttpURLConnection
            connection!!.connectTimeout = networkTimeout

            val responseCode = connection!!.getResponseCode()

            if (responseCode == HttpURLConnection.HTTP_OK) {
                val data = connection!!.inputStream.bufferedReader().readText()
                val activityFlightLogResponse = Json.decodeFromString(ActivityFlightLogResponse.serializer(), data)
                if (activityFlightLogResponse.flightLog != "") {
                    handler?.let { it.sendMessage(it.obtainMessage(NetworkResponse.GET_ACTIVITY_FLIGHT_LOG.value, activityFlightLogResponse)) }
                }
                else {
                    handler?.let { it.sendMessage(it.obtainMessage(-2, "A server error occured while retriving flight record")) }
                }

            } else {
                handler?.let { it.sendMessage(it.obtainMessage(-3, "HTTP Code $responseCode (${url})")) }
            }
        }
        catch(e: Exception) {
            handler?.let { it.sendMessage(it.obtainMessage(-4, e.message)) }
        }
        finally {
            connection?.disconnect()
        }
    }

    fun uploadFlightLog(userId: Int, flightLog: FlightLog) {
        try {

            val url = "$hostServer/$userPath/flightlog"
            connection = URL(url).openConnection() as HttpURLConnection
            connection!!.connectTimeout = networkTimeout
            connection!!.doOutput = true

            // the client must generate a padding based on the userid that the server will validate
            val padding = makePadding(userId)


            val flightLogValue = flightLog.stringify()


            val postBody = Json.encodeToString(UploadFlightLogRequest(userId, flightLog.levelId, (flightLog.id ?: -1).toInt(), flightLogValue, padding))

            connection!!.setRequestProperty("Content-Type", "application/json")
            connection!!.setRequestProperty("accept", "*/*")

            connection!!.connect()

            val outStream = OutputStreamWriter(connection!!.getOutputStream())
            val bufferedWriter = BufferedWriter(outStream)
            bufferedWriter.write(postBody)
            bufferedWriter.flush()
            bufferedWriter.close()

            val responseCode = connection!!.getResponseCode()

            if (responseCode == HttpURLConnection.HTTP_OK) {
                val dataOut = connection!!.inputStream.bufferedReader().readText()
                val response = dataOut.toBoolean()
                if (response) {
                    handler?.let {
                        it.sendEmptyMessage(NetworkResponse.UPLOAD_FLIGHT_LOG.value)
                    }
                }
                else {
                    handler?.let { it.sendMessage(it.obtainMessage(-2, "A server error occured while logging activity")) }
                }
            }
            else {
                handler?.let { it.sendMessage(it.obtainMessage(-3, "HTTP Code $responseCode (${url})")) }
            }
        }
        catch(e: Exception) {
            handler?.let { it.sendMessage(it.obtainMessage(-4, if (e.message != null) e.message else e::class.simpleName)) }
        }
        finally {
            connection?.disconnect()
        }
    }

    fun downloadFlightLog(entryId: Int) {
        try {

            val padding = makePadding(entryId)

            val url = "$hostServer/$userPath/downloadflightlog/$entryId/$padding"
            connection = URL(url).openConnection() as HttpURLConnection
            connection!!.connectTimeout = networkTimeout

            val responseCode = connection!!.getResponseCode()

            if (responseCode == HttpURLConnection.HTTP_OK) {
                val data = connection!!.inputStream.bufferedReader().readText()
                val downloadFlightLogResponse = Json.decodeFromString(DownFlightLogResponse.serializer(), data)
                if (downloadFlightLogResponse.flightLog != "") {
                    handler?.let { it.sendMessage(it.obtainMessage(NetworkResponse.DOWNLOAD_FLIGHT_LOG.value, downloadFlightLogResponse)) }
                }
                else {
                    handler?.let { it.sendMessage(it.obtainMessage(-2, "A server error occured while downloading flight log")) }
                }

            } else {
                handler?.let { it.sendMessage(it.obtainMessage(-3, "HTTP Code $responseCode (${url})")) }
            }
        }
        catch(e: Exception) {
            handler?.let { it.sendMessage(it.obtainMessage(-4, e.message)) }
        }
        finally {
            connection?.disconnect()
        }
    }


    fun logActivity(userId: Int, code: ActivityCode, levelId: Int?, data: String?, flightRecord: FlightLog?) {
        try {

            val url = "$hostServer/$userPath/activity"
            connection = URL(url).openConnection() as HttpURLConnection
            connection!!.connectTimeout = networkTimeout
            connection!!.doOutput = true

            // the client must generate a padding based on the userid that the server will validate
            val padding = makePadding(userId)

            val levelIdValue: Int
            if (levelId != null) {
                levelIdValue = levelId
            }
            else {
                levelIdValue = -1
            }

            val dataValue: String
            if (data != null) {
                dataValue = data
            }
            else {
                dataValue = ""
            }

            val flightRecordValue: String
            if (flightRecord != null) {
                flightRecordValue = flightRecord.stringify()
            }
            else {
                flightRecordValue = ""
            }


            val putBody = Json.encodeToString(LogActivityRequest(userId, code.value, levelIdValue, dataValue, flightRecordValue, padding))

            connection!!.setRequestProperty("Content-Type", "application/json")
            connection!!.setRequestProperty("accept", "*/*")

            connection!!.connect()

            val outStream = OutputStreamWriter(connection!!.getOutputStream())
            val bufferedWriter = BufferedWriter(outStream)
            bufferedWriter.write(putBody)
            bufferedWriter.flush()
            bufferedWriter.close()

            val responseCode = connection!!.getResponseCode()

            if (responseCode == HttpURLConnection.HTTP_OK) {
                val dataOut = connection!!.inputStream.bufferedReader().readText()
                val logActivityResponse = dataOut.toBoolean()
                if (!logActivityResponse) {
                    handler?.let { it.sendMessage(it.obtainMessage(-2, "A server error occured while logging activity")) }
                }
            }
            else {
                handler?.let { it.sendMessage(it.obtainMessage(-3, "HTTP Code $responseCode (${url})")) }
            }
        }
        catch(e: Exception) {
            handler?.let { it.sendMessage(it.obtainMessage(-4, e.message)) }
        }
        finally {
            connection?.disconnect()
        }
    }
}

class NetworkerSSL(receiver: NetworkResponseReceiver) {

    @Serializable
    data class TestConnectionResponse(val llpovServerVersion: String, val dbConnection: Boolean)


    private var connection: HttpsURLConnection? = null
    private val handler = NetworkHandler(Looper.getMainLooper(), receiver)
//    private var hostServer = "https://52.249.217.188"
    private var hostServer = "https://192.168.0.15:7139"
    private val leaderboardPath = "api/LeaderBoard"
    private val userPath = "api/user"
    private val testPath = "api/test"
    private val trustAllCerts: Array<TrustManager>
    val networkTimeout = 7000

    init {

        // Create a trust manager that does not validate certificate chains
        trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate>? {
                return null
            }

            override fun checkClientTrusted(
                certs: Array<X509Certificate>, authType: String
            ) {
            }

            override fun checkServerTrusted(
                certs: Array<X509Certificate>, authType: String
            ) {}
        })
    }

    fun makePadding(value: String): String {
        var numeric = 0
        for (c in value) {
            numeric +=c.code
        }
        return makePadding(numeric)
    }

    fun makePadding(value: Int): String {
        val instant = Instant.now()
        val intervals = instant.epochSecond / 300
        // make key
        val key = intervals + value
        // reverse string representation of key and append two random digits
        val padding = key.toString().reversed() + (Random.nextInt(89) + 10).toString()

        return padding
    }

    fun testConn() {
        try {
            println("Connecting to " + hostServer)
            val sc = SSLContext.getInstance("SSL")
            sc.init(null, trustAllCerts, SecureRandom())
            HttpsURLConnection.setDefaultSSLSocketFactory(sc.socketFactory)
            HttpsURLConnection.setDefaultHostnameVerifier(LLPOVHostnameVerifier())

            val url = "$hostServer/$testPath"
            connection = URL(url).openConnection() as HttpsURLConnection
            connection!!.connectTimeout = networkTimeout

            val responseCode = connection!!.getResponseCode()

            if (responseCode == HttpsURLConnection.HTTP_OK) {
                val data = connection!!.inputStream.bufferedReader().readText()
                val testConnectionResponse = Json.decodeFromString(TestConnectionResponse.serializer(), data)
                val msg = handler.obtainMessage(NetworkResponse.TEST_OK.value, testConnectionResponse)
                handler.sendMessage(msg)
            } else {
                val msg = handler.obtainMessage(-3, "HTTP Code $responseCode (${url})")
                handler.sendMessage(msg)
            }
        }
        catch(e: Exception) {
            val msg = handler.obtainMessage(-4, e.message)
            handler.sendMessage(msg)
        }
        finally {
            connection?.disconnect()
        }
    }
}

interface NetworkResponseReceiver {
    fun handleNetworkMessage(msg: Message)
}

class NetworkHandler(looper: Looper, val receiver: NetworkResponseReceiver?): Handler(looper) {
    override fun handleMessage(msg: Message) {
        super.handleMessage(msg)
        receiver?.handleNetworkMessage(msg)
    }
}


