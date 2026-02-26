package com.jimjo.exodefender

import android.content.Context
import android.os.Message
import com.jimjo.exodefender.Level.LevelVersionedSerializable
import com.jimjo.exodefender.ServerConfig.getHostServer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import kotlin.String
import kotlin.collections.Map
import kotlin.math.max

const val levelsPerCampaign = 10
class LevelManager(val context: Context): NetworkResponseReceiver {

    val mainActivity = context as MainActivity

    val levelsDirPath = context.getFilesDir().getAbsolutePath().toString() + File.separator + "levels"
    val campaignStore = CampaignStore(context)
    val allLevels = mutableListOf<Level>()
    lateinit var campaigns: List<Campaign>
    lateinit var campaignByCode: Map<String, Campaign>
    lateinit var missions: List<Level>
    lateinit var missionsAnyState: List<Level>
    val milkruns = mutableListOf<Level>()
    val training = mutableListOf<Level>()
    val development = mutableListOf<Level>()
    val levelIdLookup = hashMapOf<Int, Level>()
    val levelsDir: File
    val worldManager = WorldManager(context)
    val levelsProgress: LevelProgressManager = LevelProgressManager(context, "lp")
    private val syncLock = Any()
    @Volatile private var syncInFlight = false

    init {
        levelsDir = File(levelsDirPath)
        if (!levelsDir.exists()) {
            levelsDir.mkdirs()
        }

        worldManager.readMapFiles()

    }

    fun filenameFromId(id: Int): String = "lvl${id}.dat"

    private fun tryBeginSync(): Boolean {
        synchronized(syncLock) {
            if (syncInFlight) return false
            syncInFlight = true
            return true
        }
    }

    private fun endSync() {
        synchronized(syncLock) { syncInFlight = false }
    }


    fun loadLevelsFromInternalStorage() {
        allLevels.clear()
        milkruns.clear()
        training.clear()
        development.clear()
        levelIdLookup.clear()
        worldManager.resetWorlds()

        try {
            val files = levelsDir.listFiles()

            if (files != null) {
                for (file in files) {
                    if (file.name.endsWith(".dat") && file.name.startsWith("lvl")) {
                        // read level
                        try {
                            val data = file.readText()
                            val level = createLevelFromFileSerializable(data)
                            if (level != null) {

                                // add to list of levels and lookup
                                allLevels.add(level)
                                levelIdLookup[level.id] = level

                                // build non-mission lists
                                when (level.type) {
                                    Level.LevelType.MILKRUN -> milkruns.add(level)
                                    Level.LevelType.TRAINING -> training.add(level)
                                    Level.LevelType.DEVELOPMENT -> development.add(level)
                                    else -> {}
                                }
                            }
                        }
                        catch (e: Exception) {
                            println("ERROR loading level ${file.name}, " + e.message)
                        }
                    }
                }

                // get campaigns code/name map from internal storage
                val campaignNameMap = campaignStore.load()
                val campaignResult = buildCampaigns(allLevels, campaignNameMap)

                // build all missions list
                campaigns = campaignResult.campaigns
                missions = campaignResult.validMissionsInGameOrder
                missionsAnyState = campaignResult.allMissionsInGameOrder
                campaignByCode = campaignResult.campaignByCode

                // sort lists and save level indexes
                allLevels.sort()

                milkruns.sort()
                for ((index, level) in milkruns.withIndex()) {
                    level.index = index
                    level.globalIndex = index
                }

                training.sort()
                for ((index, level) in training.withIndex()) {
                    level.index = index
                    level.globalIndex = index
                }
                development.sort()
                for ((index, level) in development.withIndex()) {
                    level.index = index
                    level.globalIndex = index
                }
                println("levels and campaigns loaded from internal storage")

            }
        }
        catch (e: Exception) {
            println("ERROR loading levels, " + e.message)
        }
    }

    fun createLevelFromFileSerializable(data: String): Level? {

        val levelVersioned = Json.decodeFromString(LevelVersionedSerializable.serializer(), data)
        val levelJson = Json.decodeFromString(Level.LevelSerializable.serializer(), levelVersioned.json)


        val world = worldManager.worldLookupById[levelJson.mapId]

        if (world != null) {
            val level = Level(levelJson.id, levelJson.campaignCode, levelJson.type, levelJson.objectiveType, levelVersioned.version, levelJson.order, world, levelJson.difficultyWeight)
            level.type = levelJson.type
            level.name = levelJson.name
            level.shipPosition.set(levelJson.shipPosition)
            level.shipDirection = levelJson.shipDirection
            level.actorTemplates.clear()
            level.actorTemplates.addAll(levelJson.actors)

            return level
        }
        else {
            mainActivity.adminLogView.printout("ERROR: Cannot load level, no such mapId: " + levelJson.mapId)
        }
        return null
    }

   fun getLatestSyncManifest(includeDevelopment: Boolean) {
        if (!tryBeginSync()) {
            mainActivity.adminLogView.printout("Manifest sync skipped (already in flight)")
            return
        }

        Thread {
            try {
                Networker(this, getHostServer(mainActivity)).getSyncManifest(includeDevelopment)
            } catch (e: Exception) {
                mainActivity.adminLogView.printout("Level manifest fetch threw: ${e.message}")
                endSync()
            }
        }.start()
    }

    fun processSyncManifest(syncManifest: Networker.SyncManifestResponse) {

        val (toDelete, toUpsert) = diffLevels(allLevels, syncManifest.levels)

        mainActivity.adminLogView.printout("Levels to delete: $toDelete")
        mainActivity.adminLogView.printout("Levels to upsert: $toUpsert")

        for (levelId in toDelete) {
            val filename = filenameFromId(levelId)
            val file = File(levelsDir, filename)
            if (file.exists()) file.delete()
        }


        when {
            toUpsert.isNotEmpty() ->
                Thread({ Networker(this, getHostServer(mainActivity)).getLevels(toUpsert) }).start()

            toDelete.isNotEmpty() -> {

                // only deleting levels so get latest levels from internal storage right now
                loadLevelsFromInternalStorage()
                mainActivity.refreshAllLevelLists()
                endSync() // ✅ pipeline complete
            }

            else ->
                // Nothing changed so ✅ pipeline complete
                endSync()
        }
    }


    fun processUpsertLevels(upsertLevels: Networker.LevelsResponse) {
        try {
            for (upsertLevel in upsertLevels.levels) {
                writeSerializableTofile(upsertLevel)
            }
            loadLevelsFromInternalStorage()
            mainActivity.refreshAllLevelLists()

        }
        catch (e: Exception) {
            mainActivity.adminLogView.printout("Error upserting levels " + e.message)
        }
        finally {
            endSync() // ✅ pipeline complete
        }
    }

    fun writeSerializableTofile(level: Networker.LevelSerializable) {
        val filename = filenameFromId(level.id)
        val target = File(levelsDir, filename)
        val tmp = File(levelsDir, "$filename.tmp")

        val str = Json.encodeToString(level)

        tmp.outputStream().use { os ->
            OutputStreamWriter(os, Charsets.UTF_8).use { w ->
                w.write(str)
            }
        }

        // atomic replace on most Android filesystems
        if (target.exists()) target.delete()
        if (!tmp.renameTo(target)) {
            // fallback: copy then delete
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
    }

//    fun writeLevelfile(level: Level) {
//        val filename = filenameFromId(level.id)
//        val outputStreamWriter = OutputStreamWriter(FileOutputStream(File(levelsDir, filename)))
//        outputStreamWriter.write(level.stringifyFull())
//        outputStreamWriter.close()
//    }

    private val levelSaveLock = Any()

    fun writeLevelfile(level: Level) {
        synchronized(levelSaveLock) {
            val filename = filenameFromId(level.id)
            val target = File(levelsDir, filename)
            val dir = target.parentFile ?: throw IllegalStateException("No parent dir for $target")

            val text = try {
                level.stringifyFull()   // build BEFORE touching the file
            } catch (t: Throwable) {
                android.util.Log.e("LevelSave", "Save FAILED: ${t.message}", t)
                return
            }

            if (text.isBlank()) {
                android.util.Log.e("LevelSave", "Refusing to write blank output for ${target.absolutePath}")
                return
            }

            val tmp = File(dir, "$filename.tmp")
            val bak = File(dir, "$filename.bak")

            // backup last good
            if (target.exists()) {
                try { target.copyTo(bak, overwrite = true) } catch (_: Throwable) {}
            }

            FileOutputStream(tmp).use { fos ->
                fos.write(text.toByteArray(Charsets.UTF_8))
                fos.fd.sync()
            }

            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
        }
    }



    fun generateNewLevelId(): Int {
        var highestLevelId = 0
        for (level in allLevels) {
            highestLevelId = max(highestLevelId, level.id)
        }
        return highestLevelId + 10
    }

    fun getLevelTypeFromString(levelTypeString: String): Level.LevelType? {
        when (levelTypeString.uppercase()) {
            Level.LevelType.MISSION.name -> return Level.LevelType.MISSION
            Level.LevelType.MILKRUN.name -> return Level.LevelType.MILKRUN
            Level.LevelType.TRAINING.name -> return Level.LevelType.TRAINING
            Level.LevelType.DEVELOPMENT.name -> return Level.LevelType.DEVELOPMENT
            else -> return null
        }
    }

    fun getObjectiveFromName(objectiveName: String): Level.ObjectiveType? {
        return Level.ObjectiveType.entries
            .firstOrNull { it.name.equals(objectiveName, ignoreCase = true) }
    }

    fun getCampaignFromLevel(level: Level): Campaign? =
        level
            .takeIf { it.type == Level.LevelType.MISSION }
            ?.campaignCode
            ?.let { campaignByCode[it] }

    fun createBlankLevel(id: Int, name: String, order: Int, objectiveType: Level.ObjectiveType, mapId: Int, difficultyWeight: Float): Boolean {

        val gameMap = worldManager.worldLookupById[mapId]

        if (gameMap != null) {
            val level = Level(
                id,
                null,
                Level.LevelType.DEVELOPMENT,
                objectiveType,
                0,
                order,
                gameMap,
                difficultyWeight
            )
            level.name = name
            level.shipPosition.set(2500f, 2500f, 375f)
            level.shipDirection = 0.0
            development.add(level)
            writeLevelfile(level)
            return true
        }
        else {
            println("ERROR: Cannot create blank level, no such mapId: " + mapId)
        }
        return false
    }

    fun getLevelListByType(type: Level.LevelType): List<Level> {
        when (type) {
            Level.LevelType.MISSION -> return missions
            Level.LevelType.MILKRUN -> return milkruns
            Level.LevelType.TRAINING -> return training
            Level.LevelType.DEVELOPMENT -> return development
        }
    }

    fun getLevelByIndex(type: Level.LevelType, index: Int): Level? {
        val levelList = getLevelListByType(type)
        if (index <= levelList.lastIndex) {
            return levelList[index]
        }
        return null
    }

    fun getNextLevelIndex(level: Level): Int? {

        val nextIndex = level.globalIndex + 1
        val levelList = getLevelListByType(level.type)
        if (nextIndex <= levelList.lastIndex) {
            return nextIndex
        }
        else {
            return null
        }
    }

    fun getNextLevel(level: Level): Level? {

        if (level.type == Level.LevelType.MILKRUN) {
            if (milkruns.lastOrNull() == level) return missions.firstOrNull()
        }

        val nextLevelIndex = getNextLevelIndex(level)
        if (nextLevelIndex != null) {
            return getLevelByIndex(level.type, nextLevelIndex)
        }
        return null
    }

    fun prevPresentedMission(level: Level, presented: List<Level>): Level? =
        if (level.globalIndex > 0) presented[level.globalIndex - 1] else null

    fun nextPresentedMission(level: Level, presented: List<Level>): Level? =
        presented.getOrNull(level.globalIndex + 1)

    fun checkIfNextLevelIsUnlocked(level: Level): Boolean {
        if (mainActivity.unlockAllLevels) return true

        val nextLevel = getNextLevel(level)
        if (nextLevel != null) {
            return checkIfLevelUnlocked(nextLevel)
        }
        return false
    }

    fun checkIfLevelUnlocked(levelId: Int): Boolean {

        if (mainActivity.unlockAllLevels) return true

        val level = levelIdLookup[levelId]
        if (level != null) {
            return checkIfLevelUnlocked(level)
        }
        return false
    }

    fun checkIfLevelUnlocked(level: Level): Boolean {

        if (mainActivity.unlockAllLevels) return true


        // check if level is already completed
        if (levelsProgress.isLevelCompleted(level.id)) return true


        if (level.type == Level.LevelType.MILKRUN) {

            // check if first milkrun
            if (level.index == 0) return true

            // check if previous milkrun completed
            if (levelsProgress.isLevelCompleted(milkruns[level.index - 1].id)) return true
        }
        else if (level.type == Level.LevelType.MISSION) {

            // check if first presented mission of game
            if (level.globalIndex == 0) return true

            // check if previous mission completed
            val prev = prevPresentedMission(level, missions)
            return prev != null && levelsProgress.isLevelCompleted(prev.id)


        }

        return false
    }

    fun copyLevelsFromRaw() {

        val rawLevelsList = listOf(
            "lvl10",
            "lvl20",
            "lvl30",
            "lvl40",
            "lvl50",
            "lvl57",
            "lvl60",
            "lvl67",
            "lvl70",
            "lvl72",
            "lvl77",
            "lvl87",
        )
        copyRawFilesToInternalStorage(context, rawLevelsList, levelsDir)
    }

    /**
    * - levels to delete
    * - levels to upsert (create/update)
    */
    fun diffLevels(
        localLevels: List<Level>,
        manifest: List<Networker.LevelManifestItem>
    ): Pair<List<Int>, List<Int>> {

        val manifestMap = manifest.associateBy { it.id }
        val localMap = localLevels.associateBy { it.id }

        val levelsToDelete = mutableListOf<Int>()
        val levelsToUpsert = mutableListOf<Int>()

        // --- 1. Check each local level ---
        for ((id, local) in localMap) {
            val m = manifestMap[id]

            when {
                // Not in manifest → remove (if type not DEVELOPMENT)
                m == null -> {
                    if (local.type != Level.LevelType.DEVELOPMENT) levelsToDelete.add(id)
//                    levelsToDelete.add(id)
                }

                // Marked deleted by server → remove
                m.isDeleted -> levelsToDelete.add(id)

                // Version changed → update
                m.version != local.version -> levelsToUpsert.add(id)

                // Versions match → nothing to do
            }
        }

        // --- 2. Manifest levels not present locally → need to upsert ---
        for ((id, m) in manifestMap) {
            val existsLocally = localMap.containsKey(id)

            if (!existsLocally && !m.isDeleted) {
                levelsToUpsert.add(id)
            }
        }

        return levelsToDelete to levelsToUpsert
    }

    data class CampaignBuildResult(
        val campaigns: List<Campaign>,
        val validMissionsInGameOrder: List<Level>,
        val allMissionsInGameOrder: List<Level>,
        val campaignByCode: Map<String, Campaign>,
    )

    fun buildCampaigns(
        levelsAll: List<Level>,
        campaignNames: Map<String, String>,
    ): CampaignBuildResult {

        // All missions (including invalid/unassigned) for admin visibility
        val allMissions = levelsAll
            .asSequence()
            .filter { it.type == Level.LevelType.MISSION }
            .toList()

        // Valid missions (must have valid campaign code)
        val validMissions = allMissions
            .asSequence()
            .filter { isValidCampaignCode(it.campaignCode) }
            .toList()

        // Group valid missions by campaign
        val byCampaign = validMissions.groupBy {
            it.campaignCode!!.trim().uppercase()
        }

        // Build campaigns (sorted) + set per-campaign index
        val campaigns = byCampaign
            .toSortedMap(compareBy<String> { it })
            .map { (code, levels) ->
                val sortedLevels = levels.sortedWith(compareBy<Level> { it.order }.thenBy { it.id })

                // index within campaign (for all valid missions, even beyond first 10)
                sortedLevels.forEachIndexed { idx, level -> level.index = idx }

                Campaign(
                    code = code,
                    name = campaignNames[code] ?: code,
                    levels = sortedLevels
                )
            }

        // Assign campaign indices (optional but great)
        campaigns.forEachIndexed { idx, c -> c.index = idx }

        val campaignByCodeBuilt: Map<String, Campaign> = campaigns.associateBy { it.code }

        // Presented missions in true game order (campaign order, then first 10)
        val presentedValidMissionsInGameOrder = campaigns.flatMap { c ->
            c.levels.take(MISSIONS_PER_CAMPAIGN)
        }

        // Assign globalIndex across presented missions
        presentedValidMissionsInGameOrder.forEachIndexed { idx, level ->
            level.globalIndex = idx
        }

        // Unassigned missions appended for admin use (these will keep globalIndex = -1)
        val unassignedMissions = allMissions
            .asSequence()
            .filter { !isValidCampaignCode(it.campaignCode) }
            .sortedWith(compareBy<Level> { it.order }.thenBy { it.id })
            .toList()

//        val allMissionsInGameOrder = presentedValidMissionsInGameOrder + unassignedMissions
        val allValidMissionsInCampaignOrder = campaigns.flatMap { it.levels }

        val allMissionsInGameOrder =
            allValidMissionsInCampaignOrder + unassignedMissions

        return CampaignBuildResult(
            campaigns = campaigns,
            validMissionsInGameOrder = presentedValidMissionsInGameOrder,
            allMissionsInGameOrder = allMissionsInGameOrder,
            campaignByCode = campaignByCodeBuilt
        )
    }

    fun getHighestUnlockedLevel(levelType: Level.LevelType): Level? {
        val levelList: List<Level>
        if (levelType == Level.LevelType.MILKRUN) levelList = milkruns
        else if (levelType == Level.LevelType.MISSION) levelList = missions
        else return null

        for (level in levelList.reversed()) {
            if (checkIfLevelUnlocked(level)) {
                return level
            }
        }
        return null
    }

    fun getHighestUnlockedLevelId(levelType: Level.LevelType): Int? {
        val highestCompletedLevel = getHighestUnlockedLevel(levelType)
        if (highestCompletedLevel != null) {
            return highestCompletedLevel.id
        }
        return null
    }

    data class MissionPos(val campaignIdx: Int, val missionIdx: Int)

    fun levelPos(level: Level): MissionPos? {
        val mIdx = level.index
        if (mIdx < 0) return null

        val code = level.campaignCode ?: return null
        val campaign = campaignByCode[code] ?: return null

        return MissionPos(campaign.index, mIdx)
    }

    fun isCampaignNavigable(campaign: Campaign): Boolean {
        val highest = getHighestUnlockedLevel(Level.LevelType.MISSION) ?: return false
        val highestPos = levelPos(highest) ?: return false
        return highestPos.campaignIdx >= campaign.index
    }

    private fun highestUnlockedCampaignIdx(): Int {
        val highest = getHighestUnlockedLevel(Level.LevelType.MISSION) ?: return -1
        return levelPos(highest)?.campaignIdx ?: -1
    }

    fun getNextNavigableCampaign(current: Campaign?): Campaign? {
        val limit = highestUnlockedCampaignIdx()
        val startIdx = current?.index ?: -1

        for (i in startIdx + 1 until campaigns.size) {
            val c = campaigns[i]
            if (c.index <= limit) return c
        }
        return null
    }

    fun getPrevNavigableCampaign(current: Campaign?): Campaign? {
        val startIdx = current?.index ?: campaigns.size

        for (i in startIdx - 1 downTo 0) {
            val c = campaigns[i]
            if (isCampaignNavigable(c)) return c
        }
        return null
    }


    private val CAMPAIGN_CODE_REGEX =
        Regex("^[A-Z](10|[1-9])$")

    fun isValidCampaignCode(code: String?): Boolean {
        if (code.isNullOrBlank()) return false
        return CAMPAIGN_CODE_REGEX.matches(code.trim().uppercase())
    }

    override fun handleNetworkMessage(msg: Message) {
        when (msg.what) {
            NetworkResponse.GET_SYNC_MANIFEST.value -> {
                val manifest = msg.obj as Networker.SyncManifestResponse


                if (manifest.success) {
                    mainActivity.adminLogView.printout("Level and campaign manifest received")

                    // Update campaigns metadata
                    campaignStore.applyFromManifest(manifest.campaigns)

                    // Update levels as needed
                    processSyncManifest(manifest)

                }
                else {
                    endSync()
                }
            }
            NetworkResponse.GET_LEVELS.value -> {
                val upsertLevels = msg.obj as Networker.LevelsResponse
                if (upsertLevels.success) {
                    mainActivity.adminLogView.printout("Upsert levels received")
                    processUpsertLevels(upsertLevels)
                }
                else {
                    endSync()
                }
            }
            -1, -2, -3 -> {
                mainActivity.adminLogView.printout("Server Error: [${msg.what}] ${msg.obj}")
                endSync()
            }
            -4 -> {
                mainActivity.adminLogView.printout("Network error occured: [${msg.what}] ${msg.obj}")
                endSync()
            }
        }
    }
}

@kotlinx.serialization.Serializable
data class CampaignMeta(
    val code: String,
    val name: String,
    val isDeleted: Boolean = false
)

@kotlinx.serialization.Serializable
data class CampaignStoreFile(
    val campaigns: List<CampaignMeta> = emptyList()
)

class CampaignStore(private val context: Context) {

    private val fileName = "campaigns.json"
    private val tmpName = "campaigns.json.tmp"

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
    }

    fun load(): MutableMap<String, String> {
        val file = File(context.filesDir, fileName)
        if (!file.exists()) return mutableMapOf()

        return try {
            val text = file.readText()
            val store = json.decodeFromString(CampaignStoreFile.serializer(), text)
            store.campaigns
                .filter { !it.isDeleted }
                .associate { it.code to it.name }
                .toMutableMap()
        } catch (e: Exception) {
            // If corrupt, fail safe: return empty
            mutableMapOf()
        }
    }

    fun applyFromManifest(items: List<Networker.CampaignManifestItem>): MutableMap<String, String> {
        // Load current
        val map = load()

        // Apply updates
        for (c in items) {
            val code = c.code.trim().uppercase()
            if (c.isDeleted) {
                map.remove(code)
            } else {
                map[code] = c.name
            }
        }

        // Persist whole thing (small)
        save(map)

        return map
    }

    private fun save(map: Map<String, String>) {
        val campaigns = map.entries
            .sortedBy { it.key } // nice for readability/debugging
            .map { CampaignMeta(code = it.key, name = it.value, isDeleted = false) }

        val payload = CampaignStoreFile(campaigns)

        val outFile = File(context.filesDir, fileName)
        val tmpFile = File(context.filesDir, tmpName)

        val text = json.encodeToString(CampaignStoreFile.serializer(), payload)

        // Atomic-ish write: write tmp then rename
        tmpFile.writeText(text)
        if (outFile.exists()) outFile.delete()
        tmpFile.renameTo(outFile)
    }


}
