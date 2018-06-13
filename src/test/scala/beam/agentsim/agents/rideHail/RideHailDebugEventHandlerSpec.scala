package beam.agentsim.agents.rideHail

import beam.agentsim.agents.GenericEventsSpec
import org.scalatest.Matchers

class RideHailDebugEventHandlerSpec extends GenericEventsSpec with Matchers {

  "A RideHail debug handler " must {
    "detect abnormalities " in {
      val debugHandler = new RideHailDebugEventHandler(this.eventManager)

      processHandlers(List(debugHandler))


      val rhAbnorms = debugHandler.collectAbnormalities()

      rhAbnorms should not be empty
      //TODO: add value assertions
    }
  }
}
