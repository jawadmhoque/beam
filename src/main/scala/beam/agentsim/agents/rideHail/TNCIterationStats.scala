package beam.agentsim.agents.rideHail

import beam.agentsim.agents.rideHail.RideHailingManager.RideHailingAgentLocation
import beam.agentsim.infrastructure.{TAZ, TAZTreeMap}
import beam.router.BeamRouter.Location
import beam.utils.DebugLib
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.vehicles

import scala.collection.concurrent.TrieMap
import scala.collection.mutable
import scala.collection.mutable.{ArrayBuffer, ListBuffer}

case class TNCIterationStats(rideHailStats: mutable.Map[String, ArrayBuffer[Option[RideHailStatsEntry]]],
                             tazTreeMap: TAZTreeMap,
                             timeBinSizeInSec: Double,
                             numberOfTimeBins: Int) {


  def whichCoordToRepositionTo(vehiclesToReposition: Vector[RideHailingAgentLocation],
                               repositionCircleRadiusInMeters: Int,
                               tick: Double, timeHorizonToConsiderForIdleVehiclesInSec: Int):
  Vector[(Id[vehicles.Vehicle], Location)] = {

    val tazVehicleMap = mutable.Map[TAZ, ListBuffer[Id[vehicles.Vehicle]]]()

    // Vehicle Grouping in Taz
    vehiclesToReposition.foreach { rhaLoc =>
      val vehicleTaz = tazTreeMap.getTAZ(rhaLoc.currentLocation.loc.getX, rhaLoc.currentLocation.loc.getY)

      tazVehicleMap.get(vehicleTaz) match {
        case Some(lov: ListBuffer[Id[vehicles.Vehicle]]) =>
          lov += rhaLoc.vehicleId

        case None =>
          val lov = ListBuffer[Id[vehicles.Vehicle]]()
          lov += rhaLoc.vehicleId
          tazVehicleMap.put(vehicleTaz, lov)
      }
    }

    val result=tazVehicleMap.map {
      case (taz: TAZ, lov: ListBuffer[Id[vehicles.Vehicle]]) => // TODO: ZB remove case
        val listOfTazInRadius = tazTreeMap.getTAZInRadius(taz.coord.getX, taz.coord.getY, repositionCircleRadiusInMeters)

        val tazPriorityQueue = mutable.PriorityQueue[TazScore]()((tazScore1, tazScore2) => tazScore1.score.compare(tazScore2.score))

        listOfTazInRadius.forEach { (tazInRadius) =>
          val startTimeBin = getTimeBin(tick)
          val endTimeBin = getTimeBin(timeHorizonToConsiderForIdleVehiclesInSec)

          val score = (startTimeBin to endTimeBin).map(
            getRideHailStatsInfo(tazInRadius.tazId, _) match {
              case Some(rideHailStats) =>
                rideHailStats.sumOfWaitingTimes

              case _ =>
                0
            }
          ).sum

          if (score>0) {
            DebugLib.emptyFunctionForSettingBreakPoint()
          }

          tazPriorityQueue.enqueue(TazScore(tazInRadius, score))
        }

        lov.zip(tazPriorityQueue.take(lov.size).map(_.taz.coord))

    }.flatten.toVector



    result

      //.map{ (tazScore,vehicleId) =>


      // }

      // ) .zipWithIndex.map { tws => //(tazScore,index) => // TODO: ZB use pair here
      //   val index = tws._2
      //   val tazScore = tws._1
      //(lov(index), tazScore.taz.coord)


      //   }
      /// }.flatten.toVector


      // for all vehicles to reposition, group them by TAZ (k vehicles for a TAZ)
      // 1.) find all TAZ in radius
      // 2.) score them according to total waiting time
      //   3.) take top 3 and assign according to weights more or less to them
      // 4.)
      /*
    use tazTreeMap.getTAZInRadius(x,y,radius).
    //vehiclesToReposition: v1, v2, v3, v4, v5
    // taz1 -> v1,v2 (list)
    // taz2 -> v3,v4,v5 (list)
        add inpt to method: tick, timeHorizonToConsiderInSecondsForIdleVehicles
        tazVehicleGroup= group vehicles by taz -> taz -> vehicles
        for each taz in tazVehicleGroup.key{
            for all tazInRadius(taz, repositionCircleRadisInMeters){
                     add scores for bins tick to    timeHorizonToConsiderInSecondsForIdleVehicles.waitingTimes
                     assign score to TAZ
            }
            scores = Vector((tazInRadius,score)    taz1 -> score1, taz2 -> score2, etc. (best scores are taz9, taz10) -> assign taz9.coord to v1 and taz10.coord to v2
            -> assing to each vehicle in tazVehicleGroup(taz) the top best vehicles.
        }
     */

    }



  def getRideHailStatsInfo(coord: Coord, timeBin: Int): Option[RideHailStatsEntry] = {
    val tazId = tazTreeMap.getTAZ(coord.getX, coord.getY).tazId

    getRideHailStatsInfo(tazId, timeBin)
  }

  def getRideHailStatsInfo(tazId: Id[TAZ], timeBin: Int): Option[RideHailStatsEntry] = {

    rideHailStats.get(tazId.toString).flatMap(ab => ab(timeBin))
  }

  // TODO: implement according to description
  def getIdleTAZRankingForNextTimeSlots(startTime: Double, duration: Double): Vector[(TAZ, Double)] = {
    // start at startTime and end at duration time bin

    // add how many idle vehicles available
    // sort according to score
    ???
  }


  def getVehiclesWhichAreBiggestCandidatesForIdling(idleVehicles: TrieMap[Id[vehicles.Vehicle], RideHailingAgentLocation],
                                                    maxNumberOfVehiclesToReposition: Double,
                                                    tick: Double,
                                                    timeHorizonToConsiderForIdleVehiclesInSec: Int): Vector[RideHailingAgentLocation] = {
    // #######start algorithm: only look at 20min horizon and those vehicles which are located in areas with high scores should be selected for repositioning
    // but don't take all of them, only take percentage wise - e.g. if scores are TAZ-A=50, TAZ-B=40, TAZ-3=10, then we would like to get more people from TAZ-A than from TAZ-B and C.
    // e.g. just go through 20min


    /*
    priorityQueue=(ordering by score, values are vehicles).
    for (vehicle <-idleVehicles){
    var idleScore=0
      for (t<-startTimeBin to timeHorizonToConsiderInSecondsForIdleVehicles_bin)
          val rideHailStatsEntry=getRideHailStatsInfo(t, vehicle.coor)
          idleScore+=rideHailStatsEntry.sumOfIdlingVehicles
      }
      priorityQueue.add(score, vehicle)
    }
    vehicles <- priorityQueue.takeHighestScores(maxNumberOfVehiclesToReposition)
    => this is result.
    */

    val priorityQueue = mutable.PriorityQueue[VehicleLocationScores]()((vls1, vls2) => vls1.score.compare(vls2.score))

    idleVehicles.foreach {
      case (vId, rhLoc) =>

        val startTimeBin = getTimeBin(tick)
        val endTimeBin = getTimeBin(timeHorizonToConsiderForIdleVehiclesInSec)

        val idleScore = (startTimeBin to endTimeBin).map(
          getRideHailStatsInfo(rhLoc.currentLocation.loc, _) match {
            case Some(statsEntry) =>
              statsEntry.sumOfWaitingTimes

            case _ =>
              0
          }
        ).sum

        if (idleScore>0) {
          DebugLib.emptyFunctionForSettingBreakPoint()
        }

        priorityQueue.enqueue(VehicleLocationScores(vId, rhLoc, idleScore)) // TODO: ZB: remove vId?
    }

    val listOfLocations = priorityQueue.take(maxNumberOfVehiclesToReposition.toInt).map {
      case (vls: VehicleLocationScores) =>
        vls.rideHailingAgentLocation
    }.toVector


    listOfLocations

    //
    // go through vehicles
    // those vehicles, which are located in areas with high number of idling time in future from now, should be moved
    // the longer the waiting time in future, the l
    // just look at smaller repositionings
  }


  private def getTimeBin(time: Double): Int = {
    (time / timeBinSizeInSec).toInt
  }

  def getWithDifferentMap(differentMap: mutable.Map[String, ArrayBuffer[Option[RideHailStatsEntry]]]): TNCIterationStats = {
    TNCIterationStats(differentMap, tazTreeMap, timeBinSizeInSec, numberOfTimeBins)
  }

  def printMap(): Unit = {
    println("TNCIterationStats:")
    rideHailStats.values.head.foreach(println)
  }
}

object TNCIterationStats {
  def merge(statsA: TNCIterationStats, statsB: TNCIterationStats): TNCIterationStats = {
    val result = mutable.Map[String, ArrayBuffer[Option[RideHailStatsEntry]]]()

    val tazSet = statsA.rideHailStats.keySet.union(statsB.rideHailStats.keySet)

    for (taz <- tazSet) {
      //val bufferA=statsA.rideHailStats.get(taz).getOrElse(mutable.ArrayBuffer.fill(numberOfTimeBins)(None))
      //val bufferB=statsB.rideHailStats.get(taz).getOrElse(mutable.ArrayBuffer.fill(numberOfTimeBins)(None))
      result.put(taz, mergeArrayBuffer(statsA.rideHailStats(taz), statsB.rideHailStats(taz)))
    }

    statsA.getWithDifferentMap(result)
  }

  def mergeArrayBuffer(bufferA: ArrayBuffer[Option[RideHailStatsEntry]], bufferB: ArrayBuffer[Option[RideHailStatsEntry]]): ArrayBuffer[Option[RideHailStatsEntry]] = {
    val result = ArrayBuffer[Option[RideHailStatsEntry]]()

    for (i <- bufferA.indices) {
      bufferA(i) match {

        case Some(rideHailStatsEntryA) =>

          bufferB(i) match {

            case Some(rideHailStatsEntryB) =>
              result += Some(rideHailStatsEntryA.average(rideHailStatsEntryB))

            case None =>
              result += Some(rideHailStatsEntryA)
          }

        case None =>

          bufferB(i) match {

            case Some(rideHailStatsEntryB) =>
              result += Some(rideHailStatsEntryB)

            case None =>
              result += None
          }
      }
    }

    result
  }
}

case class VehicleLocationScores(vehicleId: Id[vehicles.Vehicle], rideHailingAgentLocation: RideHailingAgentLocation, score: Double)

case class TazScore(taz: TAZ, score: Double)
