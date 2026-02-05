package com.jimjo.exodefender

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.*
import kotlin.compareTo

const val MISSIONS_PER_CAMPAIGN = 10

class Level(val id: Int, var campaignCode: String? = null, var type: LevelType, val version: Int, var order: Int, val world: World): Comparable<Level> {

    @Serializable
    enum class LevelType {
        @SerialName("MISSION")
        MISSION,
        @SerialName("MILKRUN")
        MILKRUN,
        @SerialName("TRAINING")
        TRAINING,
        @SerialName("DEVELOPMENT")
        DEVELOPMENT
    }

    @Serializable
    data class LevelSerializable(
        val id: Int,
        var campaignCode: String? = null,
        val type: LevelType,
        val name: String,
        val order: Int,
        val mapId: Int,
        val shipPosition: Vec3,
        val shipDirection: Double,
        val actors: MutableList<ActorTemplate>
    )
    @Serializable
    @JsonIgnoreUnknownKeys
    data class LevelVersionedSerializable(
        val id: Int,
        val version: Int,
        val updatedAt: String,
        val json: String,
    )

    var parentRenderer: GameGLRenderer? = null
    val editEngine = LevelEditEngine(this, world)

    var name = "Level $id"
    val shipPosition = Vec3()
    var shipDirection = 0.0
    val actorTemplates = mutableListOf<ActorTemplate>()

    var nextFriendlyStructureId: Int = 1

    var index = -1 // zero-based position within campaign
    var globalIndex: Int = -1   // zero-based position in *presented* mission sequence

    // TODO logic to unlock and lock levels
    var unlocked = true

    fun loadGameMap() {
        world.loadLevel(this)
    }

    fun reset() {
        world.reset()
    }

    fun initialize(parentRenderer: GameGLRenderer) {
        this.parentRenderer = parentRenderer
        editEngine.parentRenderer = parentRenderer
    }

    fun findFriendlyStructureTemplate(id: Int): FriendlyStructureTemplate? =
        actorTemplates
            .filterIsInstance<FriendlyStructureTemplate>()
            .firstOrNull { it.id == id }

    fun getDestructibleFriendlyStructureTemplateIds(): List<Int> =
        actorTemplates
            .filterIsInstance<FriendlyStructureTemplate>()
            .filter { it.destructSeconds != null }
            .map { it.id }

    fun getFirstDestructibleFriendlyStructureTemplateId(): Int? =
        getDestructibleFriendlyStructureTemplateIds().firstOrNull()

    fun getThreatSum(): Float {
        return getDifficultyParameters().enemyThreatSum
    }
    fun getDifficultyParameters(): ScoreCalculatorV1.DifficultyParameters {

        var friendlyCount = 0
        var enemyCount = 0
        var enemyThreatSum = 0f

        for (actorTemplate in actorTemplates) {
            if (actorTemplate is FriendlyTemplate) {
                friendlyCount++
            }
            else if (actorTemplate is EnemyTemplate) {
                enemyCount++
                enemyThreatSum += actorTemplate.threat
            }
        }
        return ScoreCalculatorV1.DifficultyParameters(friendlyCount, enemyCount, enemyThreatSum)
    }

    fun getDifficultyWeight(): Float {
        if (name == "Initiation") {
            println()
        }
        return ScoreCalculatorV1.difficultyWeightOnly(getDifficultyParameters())
    }

    fun stringifyInner(): String {
        return Json.encodeToString(
            LevelSerializable(id, campaignCode, type, name, order, world.mapId,shipPosition, shipDirection, actorTemplates)
        )
    }

    fun stringifyFull(): String {
        return Json.encodeToString(
            LevelVersionedSerializable(
                id,
                version,
                "n/a",
                this.stringifyInner()
            )
        )
    }

    // Rebuilds actorTemplates from runtime for editor-authored actors.
    // FriendlyStructure is template-authored (Template -> Actor), so we preserve its templates
    // and do NOT scrape runtime FriendlyStructureActor instances back into templates.
    fun writeGameMapStateToLevel() {
        if (parentRenderer != null) {
            shipPosition.set(parentRenderer!!.ship.position)
            shipDirection = parentRenderer!!.ship.yawRad
        }

        // clear all templates except FriendlyStructureTemplates
        val structureTemplates = actorTemplates.filterIsInstance<FriendlyStructureTemplate>()
        actorTemplates.clear()
        actorTemplates.addAll(structureTemplates)

        for (actor in world.actors) {
            if (actor.active && actor !is FriendlyStructureActor) {
                actor.toTemplate()?.let { actorTemplates.add(it) }
            }
        }
    }

    override fun compareTo(other: Level): Int {
        return order - other.order
    }
}

data class Campaign(
    val code: String,
    val name: String,
    val levels: List<Level>
) {
    var index: Int = -1   // derived, zero-based per campaign
}

@Serializable
enum class BlockShape { BOX, PYRAMID, CYLINDER } // collision can still be BOX/AABB for v1

@Serializable
sealed class ActorTemplate() {
    abstract val position: Vec3
    abstract val yaw: Double
    @Transient
    var log: ActorLog? = null
}
@Serializable
sealed class FriendlyTemplate: ActorTemplate() {
    override abstract val position: Vec3
    override abstract val yaw: Double
}
@Serializable
sealed class EnemyTemplate: ActorTemplate(){
    override abstract val position: Vec3
    override abstract val yaw: Double

    open abstract val threat: Float
}

@Serializable
@SerialName("Ship")
class ShipTemplate(override val position: Vec3, override val yaw: Double = 0.0): ActorTemplate()

@Serializable
@SerialName("GroundFriendly")
class GroundFriendlyTemplate(override val position: Vec3, override val yaw: Double = 0.0): FriendlyTemplate()

@Serializable
@SerialName("BuildingBlock")
data class BuildingBlockTemplate(
    val localBasePos: Vec3,          // bottom-relative to structure base
    var localYaw: Double = 0.0,
    val shape: BlockShape = BlockShape.BOX,
    val dimensions: Vec3,            // (widthX, depthY, heightZ)
    val style: String = "Default",
    @JsonNames("landingPadTop", "landableTop")
    val landingPadTop: Boolean = false,
    var civilianSpec: CivilianSpec? = null,
)

@Serializable
@SerialName("CivilianSpec")
data class CivilianSpec(
    var initialCount: Int,
    var waitingAreaLocal: Vec3,
)  {
    fun isThreatPad(): Boolean = initialCount > 0
    fun isSafePad(): Boolean = initialCount == 0
}

@Serializable
@SerialName("FriendlyStructure")
data class FriendlyStructureTemplate(
    val id: Int,
    override val position: Vec3,     // position.z = base of structure
    override var yaw: Double = 0.0,
    val blocks: List<BuildingBlockTemplate> = emptyList(),
    val hitpoints: Float = 8000f,
    var destructSeconds: Int? = null
) : FriendlyTemplate()

@Serializable
@SerialName("GroundTrainingTarget")
class GroundTargetTemplate(@Transient override val threat: Float = 0f, override val position: Vec3, override val yaw: Double = 0.0): EnemyTemplate()

@Serializable
@SerialName("EasyGroundEnemy")
class EasyGroundEnemyTemplate(@Transient override val threat: Float = 1f, override val position: Vec3, override val yaw: Double = 0.0): EnemyTemplate()

@Serializable
@SerialName("GroundEnemy")
class GroundEnemyTemplate(@Transient override val threat: Float = 1.6f, override val position: Vec3, override val yaw: Double = 0.0): EnemyTemplate()

@Serializable
@SerialName("EasyFlyingEnemy")
class EasyFlyingEnemyTemplate(@Transient override val threat: Float = 1.1f, override val position: Vec3, override val yaw: Double = 0.0, val flyingRadius: Float = 0f, val antiClockwise: Boolean = false): EnemyTemplate()

@Serializable
@SerialName("FlyingEnemy")
class FlyingEnemyTemplate(@Transient override val threat: Float = 1.7f, override val position: Vec3, override val yaw: Double = 0.0, val flyingRadius: Float = 0f, val antiClockwise: Boolean = false): EnemyTemplate()

@Serializable
@SerialName("AdvFlyingEnemy")
class AdvFlyingEnemyTemplate(@Transient override val threat: Float = 2.8f, override val position: Vec3, override val yaw: Double = 0.0, val aabbHalfX: Float = 70f, val aabbHalfY: Float = 70f, val aabbHalfZ: Float = 35f): EnemyTemplate()

