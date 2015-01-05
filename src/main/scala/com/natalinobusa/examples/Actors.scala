package com.natalinobusa.examples

// scala

import scala.concurrent.Future
import scala.concurrent.duration._

// akka
import akka.pattern.ask
import akka.util.Timeout
import akka.actor.{Props, Actor, ActorLogging}

//json goodness
import org.json4s._
import org.json4s.JsonDSL._
import org.json4s.jackson.JsonMethods._

// scalaz monad transformers
import scalaz.{OptionT, Monad}
import scalaz.OptionT._

// First attempt to describe a user DSL for collecting values
import scala.reflect.{ClassTag, Manifest}

// Inter-actor messaging
import com.natalinobusa.examples.models.Messages.{GetField, ListFields}

//// Static code generation
import com.natalinobusa.macros.expose

// metrics actor example
trait CoralActor extends Actor with ActorLogging {

  // begin: implicits and general actor init
  def actorRefFactory = context

  implicit def executionContext = actorRefFactory.dispatcher
  implicit val timeout = Timeout(10.milliseconds)

  def askActor(a: String, msg:Any)    =  actorRefFactory.actorSelection(a).ask(msg)

  implicit val formats = org.json4s.DefaultFormats

  implicit val futureMonad = new Monad[Future] {
    def point[A](a: => A): Future[A] = Future.successful(a)
    def bind[A, B](fa: Future[A])(f: A => Future[B]): Future[B] = fa flatMap f
  }

  // Future[Option[A]] to Option[Future, A] using the OptionT monad transformer
  def  getActorField[A](actorPath:String, field:String)(implicit mf: Manifest[A]) = {
    val value = askActor(actorPath,GetField(field)).mapTo[JValue].map(json => (json \ field).extractOpt[A])
    optionT(value)
  }
  def  getInputField[A](jsonValue: JValue)(implicit mf: Manifest[A]) = {
    val value = Future.successful(jsonValue.extractOpt[A])
    optionT(value)
  }

  // todo: ideas
  // can you fold this with become/unbecome and translate into a tell pattern rather the an ask pattern?

  def process: JObject => OptionT[Future, Unit]
  def emit: JObject => Unit
  val doNotEmit = {json:JObject => {}}

  def jsonData: Receive = {
    // triggers (sensitivity list)
    case json: JObject =>
      val stage = process(json)

      // set the stage in motion ...
      val r = stage.run

      r.onSuccess {
        case Some(_) => emit(json)
        case None => log.warning("some variables are not available")
      }

      r.onFailure {
        case _ => log.warning("oh no, timeout or other serious exceptions!")
      }

  }

  def receive = jsonData orElse stateModel

  // everything is json
  // everything is described via json schema

  def stateModel:Receive

  val emptyJsonSchema = parse("""{"title":"json schema", "type":"object"}""")
  def noModelExposed:Receive = {
    case ListFields =>
      sender.!(emptyJsonSchema)

    case GetField(_) =>
      sender.!(JNothing)
  }
}


//an actor with state
class HistogramActor extends CoralActor {

  // user defined state
  // todo: the given state should be persisted

  var count    = 0L
  var avg      = 0.0
  var sd       = 0.0
  var `var`    = 0.0

  def stateModel = expose(
    ("count", "integer", count),
    ("avg",   "number",  avg),
    ("sd",    "number",  {Math.sqrt(`var`)} ),
    ("var",   "number",  `var`)
  )

  // private variables not exposed
  var avg_1    = 0.0

  def process = {
    json: JObject =>
      for {
      // from trigger data
        value <- getInputField[Double](json \ "amount")
      } yield {
        // compute (local variables & update state)
        count match {
          case 0 =>
            count = 1
            avg_1 = value
            avg = value
          case _ =>
            count += 1
            avg_1 = avg
            avg = avg_1 * (count - 1) / count + value / count
        }

        // descriptive variance
        count match {
          case 0 =>
            `var` = 0.0
          case 1 =>
            `var` = (value - avg_1) * (value - avg_1)
          case _ =>
            `var` = ((count - 2) * `var` + (count - 1) * (avg - avg) * (avg_1 - avg) + (value - avg) * (value - avg)) / (count - 1)
        }
      }
  }

  def emit = doNotEmit
}


// todo: the events actor becomes my actor dispatcher
// this actor give an idea about how to spread events to other actors
class EventsActor extends Actor with ActorLogging {
  def actorRefFactory = context

  // create and start our transform root actor
  val histogramGroupActorRef = actorRefFactory.actorOf(GroupByActor[HistogramActor]("city"), "histogram")
  val checkActorRef = actorRefFactory.actorOf(ZscoreActor("/user/events/histogram", "city", "amount", 2.0), "transforms")

  // essentially it runs a list of actors and tell them about the incoming event
  def receive = {
    case json:JObject =>
      log.debug(s"dispatching! $json")
      histogramGroupActorRef ! json
      checkActorRef ! json
  }
}

class GroupByActor[T <: Actor](by: String)(implicit m: ClassTag[T]) extends Actor with ActorLogging {
  def actorRefFactory = context

  implicit def executionContext = actorRefFactory.dispatcher
  implicit val timeout = Timeout(100.milliseconds)

  implicit val formats = org.json4s.DefaultFormats

  def receive = {
    // todo: group_by support more than a level into dynamic actors tree
    case json: JObject =>
      for {
        value   <- (json \ by).extractOpt[String]
      } yield {
        // create if it does not exist
        actorRefFactory.child(value) match
        {
          case Some(actorRef) => actorRef ! json
          case None           => actorRefFactory.actorOf(Props[T], value) ! json

        }
      }
  }
}

object GroupByActor {
  def apply[T <: Actor](by: String)(implicit m: ClassTag[T]): Props = Props(new GroupByActor[T](by))
}

object ZscoreActor {
  def apply(ac: String, by:String, field: String, score:Double): Props = Props(new ZscoreActor(ac,by,field, score))
}

// metrics actor example
class ZscoreActor(ac: String, by:String, field: String, score:Double) extends CoralActor {

  var outlier: Boolean = _

  def stateModel = expose( ("outlier", "integer", outlier) )

  def process = {
    json: JObject =>
      for {
        // from trigger data
        byValue <- getInputField[String](json \ by)
        value <- getInputField[Double](json \ field)

        // from other actors
        avg <- getActorField[Double](s"$ac/$byValue", "avg")
        std <- getActorField[Double](s"$ac/$byValue", "sd")

        //alternative syntax from other actors multiple fields
        //(avg,std) <- getActorField[Double](s"/user/events/histogram/$city", List("avg", "sd"))
      } yield {
        // compute (local variables & update state)
        val th = avg + score * std
        outlier = value > th
      }
  }

  def emit =
  {
    json: JObject =>
      if (outlier) {
        // produce emit my results (dataflow)
        // need to define some json schema, maybe that would help
        val result = ("outlier" -> outlier)

        // what about merging with input data?
        val js = render(result) merge json

        //dispatch according to registered dataflow
        //dispatcher ! js
        log.warning(compact(js))
      }
  }

}


