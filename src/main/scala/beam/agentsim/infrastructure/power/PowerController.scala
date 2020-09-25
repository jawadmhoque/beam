package beam.agentsim.infrastructure.power

import beam.cosim.helics.BeamFederate
import beam.sim.BeamServices
import beam.sim.config.BeamConfig
import org.slf4j.{Logger, LoggerFactory}

import scala.util.control.NonFatal
import scala.util.{Failure, Try}

class PowerController(beamServices: BeamServices, beamConfig: BeamConfig) {
  import PowerController._
  private val logger: Logger = LoggerFactory.getLogger(classOf[PowerController])

  private[power] lazy val beamFederateOption: Option[BeamFederate] = Try {
    logger.debug("Init PowerController resources...")
    BeamFederate.loadHelics
    BeamFederate.getInstance(beamServices)
  }.recoverWith {
    case e =>
      logger.error("Cannot init BeamFederate: {}", e.getMessage)
      Failure(e)
  }.toOption

  lazy val isConnectedToGrid: Boolean = beamFederateOption.isDefined

  /**
    * Publishes required power to the grid
    *
    * @param requiredPower power (in joules) over planning horizon
    */
  def publishPowerOverPlanningHorizon(requiredPower: Double, currentTime: Int): Unit = {
    logger.debug("Sending power over planning horizon {} to the grid at time {}...", requiredPower, currentTime)
    beamFederateOption
      .fold(logger.warn("Not connected to grid, nothing was sent")) { beamFederate =>
        beamFederate.publishPowerOverPlanningHorizon(requiredPower)
      }
  }

  /**
    * Obtains physical bounds from the grid
    *
    * @param currentTime current time
    * @return tuple of PhysicalBounds and Int (next time)
    */
  def obtainPowerPhysicalBounds(currentTime: Int): (PhysicalBounds, Int) = {
    logger.debug("Obtaining power from the grid at time {}...", currentTime)
    val (bounds, nextTime) =
      beamFederateOption
        .map { beamFederate =>
          val nextTime = beamFederate.syncAndMoveToNextTimeStep(currentTime)
          val value = beamFederate.obtainPowerFlowValue
          (PhysicalBounds(value, value, 0), nextTime)
        }
        .getOrElse {
          logger.warn("Not connected to grid, falling to default physical bounds (0.0)")
          defaultPowerPhysicalBounds(currentTime)
        }
    logger.debug("Obtained power from the grid {}...", bounds)
    (bounds, nextTime)
  }

  def close(): Unit = {
    logger.debug("Release PowerController resources...")
    beamFederateOption
      .fold(logger.warn("Not connected to grid, just releasing helics resources")) { beamFederate =>
        beamFederate.close()
      }

    try {
      logger.debug("Destroying BeamFederate")
      BeamFederate.destroyInstance()
    } catch {
      case NonFatal(ex) =>
        logger.error(s"Cannot destroy BeamFederate: ${ex.getMessage}")
    }
  }

  def defaultPowerPhysicalBounds(currentTime: Int): (PhysicalBounds, Int) = {
    val fedTimeStep = beamConfig.beam.cosim.helics.timeStep
    (PhysicalBounds.default, fedTimeStep * (1 + (currentTime / fedTimeStep)))
  }
}

object PowerController {
  case class PhysicalBounds(minPower: Double, maxPower: Double, price: Double)

  object PhysicalBounds {
    val default: PhysicalBounds = PhysicalBounds(0.0, 0.0, 0)
  }
}
