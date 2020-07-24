package beam.utils.analysis

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

import beam.agentsim.agents.vehicles.BeamVehicleType
import beam.agentsim.agents.vehicles.VehicleProtocol.StreetVehicle
import beam.agentsim.events.SpaceTime
import beam.router.BeamRouter.{Location, RoutingRequest, RoutingResponse}
import beam.router.FreeFlowTravelTime
import beam.router.Modes.BeamMode
import beam.router.graphhopper.GraphHopperRouteResolver
import beam.router.r5.{R5Wrapper, WorkerParameters}
import beam.sim.BeamHelper
import beam.sim.population.{AttributesOfIndividual, HouseholdAttributes}
import beam.utils.csv.CsvWriter
import beam.utils.map.{GpxPoint, GpxWriter}
import beam.utils.{FileUtils, ProfilingUtils}
import com.conveyal.osmlib.OSM
import com.graphhopper.{GHRequest, GHResponse}
import org.matsim.api.core.v01.{Coord, Id}

import scala.collection.JavaConverters._
import scala.collection.mutable.ListBuffer

/**
 * #2804 Get R5 vs. CCH Graphhopper performance for urbansim routes
 * Run from gradle:
 * ./gradlew :execute \
 *   -PmainClass=beam.utils.analysis.R5vsCCHPerformance \
 *   -PappArgs="['--config', 'test/input/texas/austin-prod-200k.conf', '--plans', 'path/to/plans.csv']"
 *
 */
object R5vsCCHPerformance extends BeamHelper {

  private val baseRoutingRequest: RoutingRequest = {
    val personAttribs = AttributesOfIndividual(
      householdAttributes = HouseholdAttributes("48-453-001845-2:117138", 70000.0, 1, 1, 1),
      modalityStyle = None,
      isMale = true,
      availableModes = Seq(BeamMode.CAR),
      valueOfTime = 17.15686274509804,
      age = None,
      income = Some(70000.0)
    )
    RoutingRequest(
      originUTM = new Location(2961475.272057291, 3623253.4635826824),
      destinationUTM = new Location(2967932.9521744307, 3635449.522501624),
      departureTime = 30600,
      withTransit = true,
      streetVehicles = Vector.empty,
      attributesOfIndividual = Some(personAttribs)
    )
  }

  def main(args: Array[String]): Unit = {
    val (arguments, cfg) = prepareConfig(args, isConfigArgRequired = true)

    val workerParams: WorkerParameters = WorkerParameters.fromConfig(cfg)
    val r5Wrapper = new R5Wrapper(workerParams, new FreeFlowTravelTime, travelTimeNoiseFraction = 0)

    val utm2Wgs = workerParams.geo.utm2Wgs
    val timestampStr =
      DateTimeFormatter.ofPattern("YYYY-MM-dd__HH-mm-ss").format(LocalDateTime.now())

    val outputDir = s"${workerParams.beamConfig.beam.outputs.baseOutputDirectory}/R5vsGH__$timestampStr"
    FileUtils.createDirectoryIfNotExists(outputDir)
    val gpxOutputDir = s"$outputDir/gpx"
    FileUtils.createDirectoryIfNotExists(gpxOutputDir)

    val ods = readPlans(arguments.plansLocation.get)
    val odsCount = ods.size
    logger.info(s"*R5vsGH* Origin-Destination pairs count: $odsCount")

    // R5

    val r5Responses = ListBuffer.empty[RoutingResponse]
    val r5Stats = ListBuffer.empty[ResultRouteStats]
    ProfilingUtils.timed("*R5* performance check", x => logger.info(x)) {
      var i: Int = 0
      ods.foreach { p =>
        val (origin, dest) = p
        i += 1

        if (i % 1000 == 0) {
          logger.info(s"*R5* ODs processed: $i of $odsCount (${math floor (100.0 * i / odsCount)}%)")
        }

        val carStreetVehicle = getStreetVehicle(
          s"dummy-car-for-r5-vs-cch-$i",
          BeamMode.CAV,
          origin
        )

        val req = baseRoutingRequest.copy(
          originUTM = origin,
          destinationUTM = dest,
          streetVehicles = Vector(carStreetVehicle),
          withTransit = false
        )
        val (r5Resp, computationTime) = ProfilingUtils.timed { r5Wrapper.calcRoute(req) }
        r5Responses += r5Resp

        val travelPath = r5Resp.itineraries.head.legs.head.beamLeg.travelPath
        val numberOfLinks = travelPath.linkIds.size
        val distance = travelPath.distanceInM
        val travelTime = travelPath.linkTravelTime.sum.toLong  // time is in seconds

        val (origWgs, destWgs) = (utm2Wgs.transform(origin), utm2Wgs.transform(dest))

        //noinspection DuplicatedCode
        r5Stats += ResultRouteStats(
          idx             = i,
          originLat       = origWgs.getY,
          originLon       = origWgs.getX,
          destinationLat  = destWgs.getY,
          destinationLon  = destWgs.getX,
          numberOfLinks   = numberOfLinks,
          distance        = distance,
          travelTime      = travelTime,
          computationTime = computationTime
        )
      }
    }
    logger.info("*R5* performance check completed. Routes count: {}", r5Responses.size)

    r5Responses.zipWithIndex.foreach { case (r5Resp, r5RespIdx) =>
      val r5GpxPoints: Seq[GpxPoint] = for {
        itinerary <- r5Resp.itineraries
        leg       <- itinerary.legs
        linkId    <- leg.beamLeg.travelPath.linkIds
        link      = workerParams.networkHelper.getLinkUnsafe(linkId)
        wgsCoord  = utm2Wgs.transform(link.getCoord)
      } yield GpxPoint(s"$linkId", wgsCoord)

      val r5GpxFile = gpxOutputDir + s"/r5_$r5RespIdx.gpx"
      GpxWriter.write(r5GpxFile, r5GpxPoints)
    }

    // GraphHopper

    val ghLocation = s"$outputDir/ghLocation"
    GraphHopperRouteResolver.createGHLocationFromR5(
      workerParams.transportNetwork,
      new OSM(workerParams.beamConfig.beam.inputDirectory + "/r5-prod/osm.mapdb"),
      ghLocation
    )
    val gh = new GraphHopperRouteResolver(ghLocation)

    val ghResponses = ListBuffer.empty[GHResponse]
    val ghErrors = ListBuffer.empty[GhErrResponse]
    val ghStats = ListBuffer.empty[ResultRouteStats]
    ProfilingUtils.timed("*GH* performance check", x => logger.info(x)) {
      var i: Int = 0
      ods.foreach { p =>
        i += 1

        if (i % 1000 == 0) {
          logger.info(s"*GH* ODs processed: $i of $odsCount (${math floor (100.0 * i / odsCount)}%)")
        }

        val (origin, dest) = p
        val (origWgs, destWgs) = (utm2Wgs.transform(origin), utm2Wgs.transform(dest))
        val ((ghReq, ghResp), computationTime) = ProfilingUtils.timed { gh.route(origWgs, destWgs) }

        if (ghResp.hasErrors) {
          ghErrors += GhErrResponse(i, ghReq, ghResp, computationTime)
        } else {
          ghResponses += ghResp

          val path = ghResp.getBest
          val numberOfLinks = path.getPoints.size()
          val distance = path.getDistance
          val travelTime = path.getTime / 1000  // time is in millis

          //noinspection DuplicatedCode
          ghStats += ResultRouteStats(
            idx             = i,
            originLat       = origWgs.getY,
            originLon       = origWgs.getX,
            destinationLat  = destWgs.getY,
            destinationLon  = destWgs.getX,
            numberOfLinks   = numberOfLinks,
            distance        = distance,
            travelTime      = travelTime,
            computationTime = computationTime
          )
        }
      }
    }
    logger.info(
      "*GH* performance check completed. Routes count: {} ({} errors)",
      ghResponses.size,
      ghErrors.size
    )

    ghResponses.zipWithIndex.foreach { case (ghResp, ghRespIdx) =>
      val ghGpxPoints: Iterator[GpxPoint] = for {
        // Note: path.getWaypoints are just origin+destination pair
        (point, pointIdx) <- ghResp.getBest.getPoints.iterator().asScala.zipWithIndex
      } yield GpxPoint(s"$ghRespIdx-$pointIdx", new Coord(point.getLon, point.getLat))

      val ghGpxFile = gpxOutputDir + s"/gh_$ghRespIdx.gpx"
      GpxWriter.write(ghGpxFile, ghGpxPoints.toList)
    }

    val r5StatsOutput = s"$outputDir/r5_stats.csv"
    writeStatsCsv(r5StatsOutput, r5Stats)
    logger.info(s"*R5* stats written to: $r5StatsOutput")

    val ghStatsOutput = s"$outputDir/gh_stats.csv"
    writeStatsCsv(ghStatsOutput, ghStats)
    logger.info(s"*GH* stats written to: $ghStatsOutput")

    val ghErrorsOutput = s"$outputDir/gh_errors.csv"
    writeGhErrorsCsv(ghErrorsOutput, ghErrors)
    logger.info(s"*GH* errors written to: $ghErrorsOutput")
  }

  //noinspection SameParameterValue
  private def getStreetVehicle(id: String, beamMode: BeamMode, location: Location): StreetVehicle = {
    val vehicleTypeId = beamMode match {
      case BeamMode.CAR | BeamMode.CAV =>
        "CAV"
      case BeamMode.BIKE =>
        "FAST-BIKE"
      case BeamMode.WALK =>
        "BODY-TYPE-DEFAULT"
      case _ =>
        throw new IllegalStateException(s"Don't know what to do with BeamMode $beamMode")
    }

    //noinspection DuplicatedCode
    StreetVehicle(
      id = Id.createVehicleId(id),
      vehicleTypeId = Id.create(vehicleTypeId, classOf[BeamVehicleType]),
      locationUTM = SpaceTime(loc = location, time = 30600),
      mode = beamMode,
      asDriver = true
    )
  }

  private def readPlans(path: String): Seq[(Location, Location)] = {
    import beam.utils.csv.GenericCsvReader

    val (iterator, closeable) = GenericCsvReader.readAs[(String, String, String)](
      path,
      { entry =>
        (
          entry.get("personId"),
          entry.get("activityLocationX"),
          entry.get("activityLocationY")
        )
      },
      { t =>
        val (personId, x, y) = t
        personId != null && x != null && x.nonEmpty && y != null && y.nonEmpty
      }
    )

    val buffer = ListBuffer.empty[(Location, Location)]

    var currentPerson: String = null
    var prevLocation: Location = null

    try {
      iterator.foreach { t =>
        val (personId, x, y) = t

        if (personId != currentPerson) {
          currentPerson = personId
          prevLocation = null
        }

        if (x.nonEmpty && y.nonEmpty) {
          val location = new Location(x.toDouble, y.toDouble)

          if (prevLocation != null) {
            if (prevLocation != null) {
              buffer += ((prevLocation, location))
            }
          }

          prevLocation = location
        }
      }
    } finally {
      closeable.close()
    }

    buffer
  }

  // stats utilities

  private val statsHeaders = IndexedSeq(
    "idx",
    "originLat",
    "originLon",
    "destinationLat",
    "destinationLon",
    "numberOfLinks",
    "distance",
    "travelTime",
    "computationTime"
  )

  private case class ResultRouteStats(
    idx: Int,
    originLat: Double,
    originLon: Double,
    destinationLat: Double,
    destinationLon: Double,
    numberOfLinks: Int,
    distance: Double,
    travelTime: Long,
    computationTime: Long
  ) {
    def asCsvRow: IndexedSeq[String] =
      IndexedSeq(
        s"$idx",
        s"$originLat",
        s"$originLon",
        s"$destinationLat",
        s"$destinationLon",
        s"$numberOfLinks",
        s"$distance",
        s"$travelTime",
        s"$computationTime"
      )
  }

  private def writeStatsCsv(path: String, stats: Seq[ResultRouteStats]): Unit = {
    FileUtils.using(new CsvWriter(path, statsHeaders)) { csv =>
      stats.foreach { s => csv.writeRow(s.asCsvRow) }
    }
  }

  // GH errors utilities

  private val ghErrorsHeaders = IndexedSeq(
    "idx",
    "originLat",
    "originLon",
    "destinationLat",
    "destinationLon",
    "computationTime",
    "message"
  )

  private case class GhErrResponse(
    idx: Int,
    request: GHRequest,
    response: GHResponse,
    computationTime: Long
  ) {
    def asCsvRow: Option[IndexedSeq[String]] = request.getPoints.asScala.toList match {
      case orig :: dest :: _ =>
        Some(IndexedSeq(
          s"$idx",
          s"${orig.lat}",
          s"${orig.lon}",
          s"${dest.lat}",
          s"${dest.lon}",
          s"$computationTime",
          s"""\"${unwindErrorMessage(response.getErrors)}\""""
        ))
      case _ => None
    }

  }

  private def writeGhErrorsCsv(path: String, errs: Seq[GhErrResponse]): Unit = {
    FileUtils.using(new CsvWriter(path, ghErrorsHeaders)) { csv =>
      errs.foreach { err =>
        err.asCsvRow.foreach(csv.writeRow(_))
      }
    }
  }

  private def unwindErrorMessage(ts: java.util.List[Throwable]): String = {
    ts.asScala.map { t =>
      val msgs = ListBuffer(t.getMessage)

      var cause = t.getCause
      while (cause != null) {
        msgs += cause.getMessage
        cause = cause.getCause
      }

      msgs.mkString(sep = " <> ")
    }.mkString(sep = " <~> ")
  }
}