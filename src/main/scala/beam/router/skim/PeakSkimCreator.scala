package beam.router.skim

import java.util.concurrent.{ConcurrentLinkedQueue, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger

import beam.agentsim.agents.modalbehaviors.ModeChoiceCalculator
import beam.agentsim.agents.vehicles.VehicleProtocol.StreetVehicle
import beam.agentsim.agents.vehicles.{BeamVehicle, BeamVehicleType, VehicleCategory}
import beam.agentsim.events.SpaceTime
import beam.agentsim.infrastructure.taz.TAZ
import beam.router.BeamRouter.RoutingRequest
import beam.router.Modes.BeamMode
import beam.router.Modes.BeamMode.{BIKE, CAR, WALK, WALK_TRANSIT}
import beam.router.model.{EmbodiedBeamLeg, EmbodiedBeamTrip}
import beam.router.r5.{R5Wrapper, WorkerParameters}
import beam.sim.BeamServices
import beam.sim.common.GeoUtils
import beam.sim.config.BeamConfig
import beam.sim.population.{AttributesOfIndividual, HouseholdAttributes, PopulationAdjustment}
import beam.utils.{DebugLib, ProfilingUtils, Statistics}
import com.typesafe.scalalogging.StrictLogging
import org.matsim.api.core.v01.{Coord, Id, Scenario}
import org.matsim.core.controler.events.IterationEndsEvent
import org.matsim.core.router.util.TravelTime

import scala.collection.JavaConverters._
import scala.collection.mutable.ArrayBuffer
import scala.util.control.NonFatal
import scala.util.{Failure, Success, Try}

class PeakSkimCreator(val beamServices: BeamServices, val config: BeamConfig, val travelTime: TravelTime)
    extends StrictLogging {
  private val scenario: Scenario = beamServices.matsimServices.getScenario
  private val dummyPersonAttributes = createDummyPersonAttribute
  private val modeChoiceCalculator: ModeChoiceCalculator =
    beamServices.modeChoiceCalculatorFactory(dummyPersonAttributes)

  private val r5Wrapper: R5Wrapper = createR5Wrapper()

  private val dummyCarVehicleType: BeamVehicleType = beamServices.beamScenario.vehicleTypes.values
    .find(theType => theType.vehicleCategory == VehicleCategory.Car && theType.maxVelocity.isEmpty)
    .get
  private val dummyBodyVehicleType: BeamVehicleType =
    beamServices.beamScenario.vehicleTypes.values.find(theType => theType.vehicleCategory == VehicleCategory.Body).get

  private val dummyBikeVehicleType: BeamVehicleType =
    beamServices.beamScenario.vehicleTypes.values.find(theType => theType.vehicleCategory == VehicleCategory.Bike).get

  private val tazs: Array[TAZ] = beamServices.beamScenario.tazTreeMap.getTAZs.toArray.sortBy(_.tazId.toString)

  private val beamModes: Array[BeamMode] = Array(BeamMode.CAR, BeamMode.BIKE, BeamMode.WALK_TRANSIT)

  private val thresholdDistanceForBikeMeteres: Double = 20 * 1.60934 * 1E3 // 20 miles to meters

  def write(iteration: Int): Unit = {
    try {
      val skimmer = new ODSkimmer(beamServices, config.beam.router.skim) {
        override def writeToDisk(event: IterationEndsEvent): Unit = {
          val filePath = event.getServices.getControlerIO.getIterationFilename(
            event.getServices.getIterationNumber,
            skimFileBaseName + ".UrbanSim.Full.csv.gz"
          )
          val hour = config.beam.urbansim.allTAZSkimsPeakHour.toInt
          val uniqueTimeBins: Seq[Int] = hour to hour
          writeFullSkims(uniqueTimeBins, event, filePath)
          logger.info(
            s"Written UrbanSim peak skims for hour ${config.beam.urbansim.allTAZSkimsPeakHour} to ${filePath}"
          )
        }
      }
      ProfilingUtils.timed(s"Populate skims for ${tazs.length}", logger.debug(_)) {
        populateSkimmer(skimmer)
      }
      ProfilingUtils.timed("Write skims to disk", logger.debug(_)) {
        skimmer.writeToDisk(new IterationEndsEvent(beamServices.matsimServices, iteration))
      }
    } catch {
      case NonFatal(ex) =>
        logger.error(s"Something wrong during skims preparation/writing: ${ex.getMessage}", ex)
    }
  }

  def isDinstanceWithinRange(mode: BeamMode, dist: Double): Boolean = {
    mode match {
      case BeamMode.CAR          => true
      case BeamMode.WALK_TRANSIT => true
      case BeamMode.BIKE =>
        dist < thresholdDistanceForBikeMeteres
      case x => throw new IllegalStateException(s"Don't know what to do with $x")
    }
  }

  def createStreetVehicle(mode: BeamMode, requestTime: Int, srcCoord: Coord): StreetVehicle = {
    val streetVehicle: StreetVehicle = mode match {
      case BeamMode.CAR =>
        StreetVehicle(
          Id.createVehicleId("dummy-car-for-skim-observations"),
          dummyCarVehicleType.id,
          new SpaceTime(srcCoord, requestTime),
          mode,
          asDriver = true
        )
      case BeamMode.BIKE =>
        StreetVehicle(
          Id.createVehicleId("dummy-bike-for-skim-observations"),
          dummyBikeVehicleType.id,
          new SpaceTime(srcCoord, requestTime),
          mode,
          asDriver = true
        )
      case BeamMode.WALK_TRANSIT =>
        StreetVehicle(
          Id.createVehicleId("dummy-body-for-skim-observations"),
          dummyBodyVehicleType.id,
          new SpaceTime(srcCoord, requestTime),
          WALK,
          asDriver = true
        )
      case x =>
        throw new IllegalArgumentException(s"Get mode ${x}, but don't know what to do with it.")
    }
    streetVehicle
  }

  private def distanceWithMargin(srcCoord: Coord, dstCoord: Coord): Double = {
    GeoUtils.distFormula(srcCoord, dstCoord) * 1.4
  }

  private def getCenters(srcTaz: TAZ, dstTaz: TAZ): (Coord, Coord) = {
    val srcCoord = if (srcTaz.tazId.equals(dstTaz.tazId)) {
      new Coord(srcTaz.coord.getX + Math.sqrt(srcTaz.areaInSquareMeters) / 3.0, srcTaz.coord.getY)
    } else {
      srcTaz.coord
    }
    val dstCoord = if (srcTaz.tazId.equals(dstTaz.tazId)) {
      new Coord(
        dstTaz.coord.getX - Math.sqrt(dstTaz.areaInSquareMeters) / 3.0,
        dstTaz.coord.getY
      )
    } else {
      dstTaz.coord
    }
    (srcCoord, dstCoord)
  }

  private def populateSkimmer(skimmer: ODSkimmer): ODSkimmer = {
    val requestTime = (config.beam.urbansim.allTAZSkimsPeakHour * 3600).toInt
    logger.info(s"There are ${tazs.length} TAZs")

    val processedAtomic = new AtomicInteger(0)
    val computedRoutes = new AtomicInteger(0)
    val failedRoutes = new AtomicInteger(0)
    val emptyItineraries = new AtomicInteger(0)

    val started = System.currentTimeMillis()

    val tazPairs = tazs.flatMap { srcTaz =>
      tazs.map { dstTaz =>
        (srcTaz, dstTaz)
      }
    }

    val onePct = (tazPairs.length.toDouble * 0.01).toInt
    logger.info(s"One percent from ${tazPairs.length} is $onePct")
    val nonEmptyRoutesPerType = Map[BeamMode, AtomicInteger](
      BeamMode.CAR          -> new AtomicInteger(0),
      BeamMode.WALK_TRANSIT -> new AtomicInteger(0),
      BeamMode.BIKE         -> new AtomicInteger(0)
    )

    val skimEvents = ProfilingUtils.timed("Creating skim events", logger.info(_)) {
      // The most outer loop will be executed in parallel `.par`
      tazPairs.par
        .flatMap {
          case (srcTaz, dstTaz) =>
            val (srcCoord, dstCoord) = getCenters(srcTaz, dstTaz)
            val dist = distanceWithMargin(srcCoord, dstCoord)
            val considerModes = beamModes.filter(mode => isDinstanceWithinRange(mode, dist))
            val streetVehicles = considerModes.map(createStreetVehicle(_, requestTime, srcCoord))
            val routingReq = RoutingRequest(
              originUTM = srcCoord,
              destinationUTM = dstCoord,
              departureTime = requestTime,
              withTransit = true,
              streetVehicles = streetVehicles,
              attributesOfIndividual = Some(dummyPersonAttributes)
            )
            val processed = processedAtomic.getAndIncrement()
            if (processed > 0 && processed % onePct == 0) {
              val diff = System.currentTimeMillis() - started
              val rps = processed.toDouble / diff * 1000 // Average per second
              val pct = 100 * processed.toDouble / tazPairs.length
              logger.info(
                s"Processed $processed routes, $pct % in $diff ms, AVG per second: $rps. Failed: ${failedRoutes
                  .get()}, empty: ${emptyItineraries.get()}, non-empty: ${computedRoutes.get}"
              )
              logger.info(s"Non-empty routes per mode: ")
              nonEmptyRoutesPerType.foreach {
                case (mode, counter) =>
                  logger.info(s"Non-empty route for $mode\t\t${counter.get()}")
              }
            }
            Try(r5Wrapper.calcRoute(routingReq)) match {
              case Failure(ex) =>
                failedRoutes.getAndIncrement()
                logger.error(s"Can't get route: ${ex.getMessage}", ex)
                None
              case Success(response) =>
                if (response.itineraries.isEmpty)
                  emptyItineraries.getAndIncrement()
                else
                  computedRoutes.getAndIncrement()
                response.itineraries.map { trip =>
                  if (considerModes.contains(trip.tripClassifier)) {
                    nonEmptyRoutesPerType.get(trip.tripClassifier).foreach(_.getAndIncrement())

                    val maybeEvent = try {
                      Some(createSkimEvent(trip.tripClassifier, requestTime, trip))
                    } catch {
                      case NonFatal(ex) =>
                        logger.error(s"Can't create skim event: ${ex.getMessage}", ex)
                        None
                    }
                    maybeEvent
                  } else None
                }
            }
        }
        .seq
        .flatten
    }
    logger.info(s"Total number of skim events: ${skimEvents.size}, failed routes: ${failedRoutes
      .get()}, empty responses: ${emptyItineraries.get()}, computed in ${System.currentTimeMillis() - started} ms")
    nonEmptyRoutesPerType.foreach {
      case (mode, counter) =>
        logger.info(s"Non-empty route for $mode\t\t${counter.get()}")
    }
    skimEvents.foreach(skimmer.handleEvent)
    skimmer
  }

  private def createSkimEvent(beamMode: BeamMode, requestTime: Int, trip: EmbodiedBeamTrip): ODSkimmerEvent = {
    // In case of CAR AND BIKE we have to create two dummy legs: walk to the CAR in the beginning and walk when CAR has arrived
    val theTrip = if (beamMode == BeamMode.CAR || beamMode == BeamMode.BIKE) {
      val actualLegs = trip.legs
      EmbodiedBeamTrip(
        EmbodiedBeamLeg.dummyLegAt(
          start = actualLegs.head.beamLeg.startTime,
          vehicleId = Id.createVehicleId("dummy-body"),
          isLastLeg = false,
          location = actualLegs.head.beamLeg.travelPath.startPoint.loc,
          mode = WALK,
          vehicleTypeId = dummyBodyVehicleType.id
        ) +:
        actualLegs :+
        EmbodiedBeamLeg.dummyLegAt(
          start = actualLegs.last.beamLeg.endTime,
          vehicleId = Id.createVehicleId("dummy-body"),
          isLastLeg = true,
          location = actualLegs.last.beamLeg.travelPath.endPoint.loc,
          mode = WALK,
          vehicleTypeId = dummyBodyVehicleType.id
        )
      )
    } else {
      trip
    }
    val generalizedTime =
      modeChoiceCalculator.getGeneralizedTimeOfTrip(theTrip, Some(dummyPersonAttributes), None)
    val generalizedCost = modeChoiceCalculator.getNonTimeCost(theTrip) + dummyPersonAttributes.getVOT(generalizedTime)
    val energyConsumption = dummyCarVehicleType.primaryFuelConsumptionInJoulePerMeter * theTrip.legs
      .map(_.beamLeg.travelPath.distanceInM)
      .sum
    logger.debug(
      s"Observing skim from ${beamServices.beamScenario.tazTreeMap
        .getTAZ(theTrip.legs.head.beamLeg.travelPath.startPoint.loc)
        .tazId} to ${beamServices.beamScenario.tazTreeMap.getTAZ(theTrip.legs.last.beamLeg.travelPath.endPoint.loc).tazId} takes ${generalizedTime} seconds"
    )
    ODSkimmerEvent(
      requestTime,
      beamServices,
      theTrip,
      generalizedTime,
      generalizedCost,
      energyConsumption
    )
  }

  private def createDummyPersonAttribute: AttributesOfIndividual = {
    val medianHouseholdByIncome = scenario.getHouseholds.getHouseholds
      .values()
      .asScala
      .toList
      .sortBy(_.getIncome.getIncome)
      .drop(scenario.getHouseholds.getHouseholds.size() / 2)
      .head
    val dummyHouseholdAttributes = new HouseholdAttributes(
      householdId = medianHouseholdByIncome.getId.toString,
      householdIncome = medianHouseholdByIncome.getIncome.getIncome,
      householdSize = 1,
      numCars = 1,
      numBikes = 1
    )
    val personVOTT = PopulationAdjustment
      .incomeToValueOfTime(dummyHouseholdAttributes.householdIncome)
      .getOrElse(config.beam.agentsim.agents.modalBehaviors.defaultValueOfTime)
    AttributesOfIndividual(
      householdAttributes = dummyHouseholdAttributes,
      modalityStyle = None,
      isMale = true,
      availableModes = Seq(CAR, WALK_TRANSIT, BIKE),
      valueOfTime = personVOTT,
      age = None,
      income = Some(dummyHouseholdAttributes.householdIncome)
    )
  }

  private def createR5Wrapper(): R5Wrapper = {
    val workerParams: WorkerParameters = WorkerParameters(
      beamConfig = config,
      transportNetwork = beamServices.beamScenario.transportNetwork,
      vehicleTypes = beamServices.beamScenario.vehicleTypes,
      fuelTypePrices = beamServices.beamScenario.fuelTypePrices,
      ptFares = beamServices.beamScenario.ptFares,
      geo = beamServices.geo,
      dates = beamServices.beamScenario.dates,
      networkHelper = beamServices.networkHelper,
      fareCalculator = beamServices.fareCalculator,
      tollCalculator = beamServices.tollCalculator
    )
    new R5Wrapper(workerParams, travelTime)
  }

}