package beam.sim

import java.io.FileNotFoundException
import java.nio.file.Paths
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

import akka.actor.ActorRef
import akka.util.Timeout
import beam.agentsim.agents.choice.mode.{ModeIncentive, PtFares}
import beam.agentsim.agents.modalbehaviors.ModeChoiceCalculator.ModeChoiceCalculatorFactory
import beam.agentsim.agents.vehicles.FuelType.FuelType
import beam.agentsim.agents.vehicles._
import beam.agentsim.infrastructure.TAZTreeMap
import beam.agentsim.infrastructure.TAZTreeMap.TAZ
import beam.router.Modes.BeamMode
import beam.sim.BeamServices.getTazTreeMap
import beam.sim.common.GeoUtils
import beam.sim.config.BeamConfig
import beam.sim.config.BeamConfig.Beam.Agentsim.Agents.ModalBehaviors
import beam.sim.metrics.Metrics
import beam.utils.BeamVehicleUtils.{readBeamVehicleTypeFile, readFuelTypeFile, readVehiclesFile}
import beam.utils.{DateUtils, NetworkHelper}
import com.google.inject.{ImplementedBy, Inject, Injector}
import org.matsim.api.core.v01.population.Person
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.controler._
import org.matsim.core.utils.collections.QuadTree
import org.matsim.households.Household
import org.matsim.vehicles.Vehicle
import org.slf4j.LoggerFactory

import scala.collection.concurrent.TrieMap
import scala.collection.mutable
import scala.concurrent.duration.FiniteDuration

@ImplementedBy(classOf[BeamServicesImpl])
trait BeamServices {
  val injector: Injector
  val controler: ControlerI
  val beamConfig: BeamConfig

  val geo: GeoUtils
  var modeChoiceCalculatorFactory: ModeChoiceCalculatorFactory
  val dates: DateUtils

  var beamRouter: ActorRef
  val rideHailTransitModes: Seq[BeamMode]
  val agencyAndRouteByVehicleIds: TrieMap[Id[Vehicle], (String, String)]
  var personHouseholds: Map[Id[Person], Household]

  var matsimServices: MatsimServices
  val modeIncentives: ModeIncentive
  val ptFares: PtFares
  var iterationNumber: Int = -1

  def startNewIteration()

  def networkHelper: NetworkHelper
  var transitFleetSizes: mutable.HashMap[String, Integer] = mutable.HashMap.empty
  def setTransitFleetSizes(tripFleetSizeMap: mutable.HashMap[String, Integer])

  def getModalBehaviors(): ModalBehaviors = {
    beamConfig.beam.agentsim.agents.modalBehaviors
  }

  def getDefaultAutomationLevel(): Option[Int] = {
    if (beamConfig.beam.agentsim.agents.modalBehaviors.overrideAutomationForVOTT) {
      Option(beamConfig.beam.agentsim.agents.modalBehaviors.overrideAutomationLevel)
    } else {
      None
    }
  }
}

class BeamServicesImpl @Inject()(val injector: Injector) extends BeamServices {

  val controler: ControlerI = injector.getInstance(classOf[ControlerI])
  val beamConfig: BeamConfig = injector.getInstance(classOf[BeamConfig])

  val geo: GeoUtils = injector.getInstance(classOf[GeoUtils])

  val dates: DateUtils = DateUtils(
    ZonedDateTime.parse(beamConfig.beam.routing.baseDate).toLocalDateTime,
    ZonedDateTime.parse(beamConfig.beam.routing.baseDate)
  )

  val rideHailTransitModes: Seq[BeamMode] =
    if (beamConfig.beam.agentsim.agents.rideHailTransit.modesToConsider.equalsIgnoreCase("all")) BeamMode.transitModes
    else if (beamConfig.beam.agentsim.agents.rideHailTransit.modesToConsider.equalsIgnoreCase("mass"))
      BeamMode.massTransitModes
    else {
      beamConfig.beam.agentsim.agents.rideHailTransit.modesToConsider.toUpperCase
        .split(",")
        .map(BeamMode.fromString)
        .toSeq
        .flatten
    }

  var modeChoiceCalculatorFactory: ModeChoiceCalculatorFactory = _
  var beamRouter: ActorRef = _
  var rideHailIterationHistoryActor: ActorRef = _

  val agencyAndRouteByVehicleIds: TrieMap[
    Id[Vehicle],
    (String, String)
  ] = TrieMap()
  var personHouseholds: Map[Id[Person], Household] = Map()

  var matsimServices: MatsimServices = _

  val modeIncentives = ModeIncentive(beamConfig.beam.agentsim.agents.modeIncentive.filePath)
  val ptFares = PtFares(beamConfig.beam.agentsim.agents.ptFare.filePath)

  def startNewIteration(): Unit = {
    iterationNumber += 1
    Metrics.iterationNumber = iterationNumber
  }

  override def setTransitFleetSizes(tripFleetSizeMap: mutable.HashMap[String, Integer]): Unit = {
    this.transitFleetSizes = tripFleetSizeMap
  }

  private val _networkHelper: NetworkHelper = injector.getInstance(classOf[NetworkHelper])

  def networkHelper: NetworkHelper = _networkHelper
}

object BeamServices {

  type FuelTypePrices = Map[FuelType, Double]

  private val logger = LoggerFactory.getLogger(this.getClass)
  implicit val askTimeout: Timeout = Timeout(FiniteDuration(5L, TimeUnit.SECONDS))

  var vehicleCounter = 1

  val defaultTazTreeMap: TAZTreeMap = {
    val tazQuadTree: QuadTree[TAZ] = new QuadTree(-1, -1, 1, 1)
    val taz = new TAZ("0", new Coord(0.0, 0.0), 0.0)
    tazQuadTree.put(taz.coord.getX, taz.coord.getY, taz)
    new TAZTreeMap(tazQuadTree)
  }

  def getTazTreeMap(filePath: String): TAZTreeMap = {
    try {
      TAZTreeMap.fromCsv(filePath)
    } catch {
      case fe: FileNotFoundException =>
        logger.error("No TAZ file found at given file path (using defaultTazTreeMap): %s" format filePath, fe)
        defaultTazTreeMap
      case e: Exception =>
        logger.error(
          "Exception occurred while reading from CSV file from path (using defaultTazTreeMap): %s" format e.getMessage,
          e
        )
        defaultTazTreeMap
    }
  }
}
