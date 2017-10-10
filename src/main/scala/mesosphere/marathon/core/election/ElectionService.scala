package mesosphere.marathon
package core.election

import akka.NotUsed
import akka.actor.ActorRef
import akka.actor.{ ActorSystem, Cancellable }
import akka.event.EventStream
import akka.stream.ClosedShape
import akka.stream.scaladsl.{ Broadcast, GraphDSL, RunnableGraph, Flow, Sink, Source }
import akka.stream.ActorMaterializer
import com.typesafe.scalalogging.StrictLogging
import kamon.Kamon
import kamon.metric.instrument.Time
import mesosphere.marathon.core.async.ExecutionContexts
import mesosphere.marathon.core.base.CrashStrategy
import mesosphere.marathon.stream.EnrichedFlow
import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Success }

/**
  * ElectionService is implemented by leadership election mechanisms.
  *
  * This trait is used in conjunction with [[ElectionCandidate]]. From their point of view,
  * a leader election works as follow:
  *
  * -> ElectionService.offerLeadership(candidate)     |      - A leader election is triggered.
  *                                                          — Once `candidate` is elected as a leader,
  *                                                            its `startLeadership` is called.
  *
  * Please note that upon a call to [[ElectionService.abdicateLeadership]], or
  * any error in any of method of [[ElectionService]], or a leadership loss,
  * [[ElectionCandidate.stopLeadership]] is called if [[ElectionCandidate.startLeadership]]
  * has been called before, and JVM gets shutdown.
  *
  * It effectively means that a particular instance of Marathon can be elected at most once during its lifetime.
  */
trait ElectionService {
  /**
    * isLeader checks whether this instance is the leader
    *
    * @return true if this instance is the leader
    */
  def isLeader: Boolean

  /**
    * localHostPort return a host:port pair of this running instance that is used for discovery.
    *
    * @return host:port of this instance.
    */
  def localHostPort: String

  /**
    * leaderHostPort return a host:port pair of the leader, if it is elected.
    *
    * @return Some(host:port) of the leader, or None if no leader exists or is known
    */
  def leaderHostPort: Option[String]

  /**
    * offerLeadership is called to candidate for leadership. It must be called by candidate only once.
    *
    * @param candidate is called back once elected or defeated
    */
  def offerLeadership(candidate: ElectionCandidate): Unit

  /**
    * abdicateLeadership is called to resign from leadership. By the time this method returns,
    * it can be safely assumed the leadership has been abdicated. This method can be called even
    * if [[offerLeadership]] wasn't called prior to that, and it will result in Marathon stop and JVM shutdown.
    */
  def abdicateLeadership(): Unit

  /**
    * Subscribe to leadership change events.
    *
    * The given actorRef will initially get the current state via the appropriate
    * [[LeadershipTransition]] message and will be informed of changes after that.
    *
    * Upon becoming a leader, [[LeadershipTransition.ElectedAsLeader]] is published. Upon leadership loss,
    * [[LeadershipTransition.Standby]] is sent.
    */
  def subscribe(self: ActorRef): Unit

  /**
    * Unsubscribe to any leadership change events for the given [[ActorRef]].
    */
  def unsubscribe(self: ActorRef): Unit
}

/**
  * ElectionCandidate is implemented by a leadership election candidate. There is only one
  * ElectionCandidate per ElectionService.
  */
trait ElectionCandidate {
  /**
    * stopLeadership is called when the candidate was leader, but was defeated. It is guaranteed
    * that calls to stopLeadership and startLeadership alternate and are synchronized.
    */
  def stopLeadership(): Unit

  /**
    * startLeadership is called when the candidate has become leader. It is guaranteed
    * that calls to stopLeadership and startLeadership alternate and are synchronized.
    */
  def startLeadership(): Unit
}

class ElectionServiceImpl(
    eventStream: EventStream,
    hostPort: String,
    leaderEventsSource: Source[LeadershipState, Cancellable],
    crashStrategy: CrashStrategy,
    electionEC: ExecutionContext
)(implicit system: ActorSystem) extends ElectionService with StrictLogging {

  import ElectionService._
  @volatile private[this] var lastState: LeadershipState = LeadershipState.Standby(None)
  implicit private lazy val materializer = ActorMaterializer()
  var leaderSubscription: Option[Cancellable] = None

  def subscribe(subscriber: ActorRef): Unit = {
    eventStream.subscribe(subscriber, classOf[LeadershipTransition])
    val currentState = if (isLeader) LeadershipTransition.ElectedAsLeader else LeadershipTransition.Standby
    subscriber ! currentState
  }

  def unsubscribe(subscriber: ActorRef): Unit = {
    eventStream.unsubscribe(subscriber, classOf[LeadershipTransition])
  }

  override def isLeader: Boolean =
    lastState == LeadershipState.ElectedAsLeader

  override def localHostPort: String = hostPort

  override def leaderHostPort: Option[String] = lastState match {
    case LeadershipState.ElectedAsLeader =>
      Some(hostPort)
    case LeadershipState.Standby(currentLeader) =>
      currentLeader
  }

  /**
    * Releases leadership
    *
    * Has no effect if called before offerLeadership
    */
  override def abdicateLeadership(): Unit = {
    leaderSubscription.foreach(_.cancel())
  }

  private def initializeStream(leadershipTransitionsFlow: Flow[LeadershipState, LeadershipTransition, NotUsed]) = {
    val localEventListenerSink = Sink.foreach[LeadershipState] { event =>
      lastState = event
    }
    val localTransitionSink = Sink.foreach[LeadershipTransition] { e =>
      eventStream.publish(e)
      if (e == LeadershipTransition.Standby) {
        logger.error("Lost leadership; crashing")
        crashStrategy.crash()
      }
    }

    val (leaderStream, leaderStreamDone) =
      RunnableGraph.fromGraph(GraphDSL.create(
        leaderEventsSource, localEventListenerSink)(
        (_, _)) { implicit b =>
          { (leaderEventsSource, localEventListenerSink) =>
            import GraphDSL.Implicits._
            // We defensively specify eagerCancel as true; if any of the components in the stream close or fail, then
            // we'll help to make it obvious by closing the entire graph (and, by consequence, crashing).
            // Akka will log all stream failures, by default.
            val stateBroadcast = b.add(Broadcast[LeadershipState](2, eagerCancel = true))
            val transitionBroadcast = b.add(Broadcast[LeadershipTransition](2, eagerCancel = true))
            leaderEventsSource ~> stateBroadcast.in
            stateBroadcast ~> localEventListenerSink
            stateBroadcast ~> leadershipTransitionsFlow ~> transitionBroadcast.in

            transitionBroadcast ~> metricsSink
            transitionBroadcast ~> localTransitionSink
            ClosedShape
          }
        }).run

    // When the leadership stream terminates, for any reason, we suicide
    leaderStreamDone.onComplete {
      case Failure(ex) =>
        logger.info("Leadership ended with failure; exiting", ex)
        crashStrategy.crash()
      case Success(_) =>
        logger.info("Leadership ended gracefully; exiting")
        crashStrategy.crash()
    }(ExecutionContexts.callerThread)

    leaderStream
  }

  /**
    * offerLeadership is called to candidate for leadership. It must be called by candidate only once.
    *
    * @param candidate is called back once elected or defeated
    */
  def offerLeadership(candidate: ElectionCandidate): Unit = {

    /**
      * Deduped event stream with current leader removed. Specified this way to maintain compatibility with the rest of
      * the code base
      */
    val leadershipTransitionsFlow =
      Flow[LeadershipState]
        .map {
          case LeadershipState.ElectedAsLeader => LeadershipTransition.ElectedAsLeader
          case _: LeadershipState.Standby => LeadershipTransition.Standby
        }
        .via(EnrichedFlow.dedup(initialFilterElement = LeadershipTransition.Standby)) // if the first elements are standby, emit nothing
        .mapAsync(1) {
          case LeadershipTransition.ElectedAsLeader =>
            Future {
              candidate.startLeadership()
              LeadershipTransition.ElectedAsLeader
            }(electionEC)
          case LeadershipTransition.Standby =>
            Future {
              candidate.stopLeadership()
              LeadershipTransition.Standby
            }(electionEC)
        }

    leaderSubscription = Some(initializeStream(leadershipTransitionsFlow))
  }
}

object ElectionService extends StrictLogging {
  private val leaderDurationMetric = "service.mesosphere.marathon.leaderDuration"

  val metricsSink = Sink.foreach[LeadershipTransition] {
    case LeadershipTransition.ElectedAsLeader =>
      val startedAt = System.currentTimeMillis()
      Kamon.metrics.gauge(leaderDurationMetric, Time.Milliseconds)(System.currentTimeMillis() - startedAt)
    case LeadershipTransition.Standby =>
      Kamon.metrics.removeGauge(leaderDurationMetric)
  }
}

/**
  * Events produced by Curator election stream; describes transitions from one leader to the next while not leader
  */
private[election] sealed trait LeadershipState
private[election] object LeadershipState {
  case object ElectedAsLeader extends LeadershipState
  case class Standby(currentLeader: Option[String]) extends LeadershipState
}

/** Local leadership events. They are not delivered via the event endpoints. */
sealed trait LeadershipTransition
object LeadershipTransition {
  case object ElectedAsLeader extends LeadershipTransition
  case object Standby extends LeadershipTransition
}
