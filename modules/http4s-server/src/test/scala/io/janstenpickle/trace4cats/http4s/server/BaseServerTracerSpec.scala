package io.janstenpickle.trace4cats.http4s.server

import cats.data.NonEmptyList
import cats.effect.{Blocker, ConcurrentEffect, Resource, Sync, Timer}
import cats.syntax.applicativeError._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.{~>, ApplicativeError, Id}
import io.janstenpickle.trace4cats.Span
import io.janstenpickle.trace4cats.`export`.RefSpanCompleter
import io.janstenpickle.trace4cats.http4s.common.Http4sStatusMapping
import io.janstenpickle.trace4cats.inject.EntryPoint
import io.janstenpickle.trace4cats.kernel.SpanSampler
import io.janstenpickle.trace4cats.model.{CompletedSpan, SpanKind, SpanStatus}
import org.http4s.client.blaze.BlazeClientBuilder
import org.http4s.dsl.Http4sDsl
import org.http4s.headers.`WWW-Authenticate`
import org.http4s.server.blaze.BlazeServerBuilder
import org.http4s.syntax.all._
import org.http4s.{Challenge, HttpApp, HttpRoutes, Response}
import org.scalacheck.{Arbitrary, Gen}
import org.scalatest.Assertion
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckDrivenPropertyChecks

import scala.collection.immutable.Queue
import scala.concurrent.duration._

abstract class BaseServerTracerSpec[F[_]: ConcurrentEffect, G[_]: Sync](
  port: Int,
  fkId: F ~> Id,
  lower: Span[F] => G ~> F,
  injectRoutes: (HttpRoutes[G], EntryPoint[F]) => HttpRoutes[F],
  injectApp: (HttpApp[G], EntryPoint[F]) => HttpApp[F],
  timer: Timer[F]
) extends AnyFlatSpec
    with ScalaCheckDrivenPropertyChecks
    with Matchers
    with ServerSyntax
    with Http4sDsl[G] {
  implicit val t: Timer[F] = timer

  implicit val responseArb: Arbitrary[G[Response[G]]] =
    Arbitrary(
      Gen.oneOf(
        List(
          Ok(),
          BadRequest(),
          Unauthorized.apply(`WWW-Authenticate`.apply(NonEmptyList.one(Challenge("", "", Map.empty)))),
          Forbidden(),
          TooManyRequests(),
          BadGateway(),
          ServiceUnavailable(),
          GatewayTimeout()
        )
      )
    )

  it should "record a span when the response is OK" in {
    val app = HttpRoutes.of[G] {
      case GET -> Root => Ok()
    }

    evaluateTrace(app, app.orNotFound) { spans =>
      spans.size should be(1)
      spans.head.name should be("GET /")
      spans.head.kind should be(SpanKind.Server)
      spans.head.status should be(SpanStatus.Ok)
    }
  }

  it should "correctly set span status when the server throws an exception" in forAll { errorMsg: String =>
    val app = HttpRoutes.of[G] {
      case GET -> Root => ApplicativeError[G, Throwable].raiseError(new RuntimeException(errorMsg))
    }

    evaluateTrace(app, app.orNotFound) { spans =>
      spans.size should be(1)
      spans.head.name should be("GET /")
      spans.head.kind should be(SpanKind.Server)
      spans.head.status should be(SpanStatus.Internal(errorMsg))
    }
  }

  it should "correctly set the span status from the http response" in forAll { response: G[Response[G]] =>
    val app = HttpRoutes.of[G] {
      case GET -> Root => response
    }

    val expectedStatus =
      Http4sStatusMapping.toSpanStatus(fkId(Span.noop[F].use(s => lower(s).apply(response))).status)

    evaluateTrace(app, app.orNotFound) { spans =>
      spans.size should be(1)
      spans.head.name should be("GET /")
      spans.head.kind should be(SpanKind.Server)
      spans.head.status should be(expectedStatus)
    }
  }

  def evaluateTrace(routes: HttpRoutes[G], app: HttpApp[G])(fa: Queue[CompletedSpan] => Assertion): Assertion = {
    def test(f: EntryPoint[F] => HttpApp[F]): Assertion =
      fkId.apply(
        (for {
          blocker <- Blocker[F]
          completer <- Resource.liftF(RefSpanCompleter[F])
          ep = EntryPoint[F](SpanSampler.always, completer)
          _ <- BlazeServerBuilder[F](blocker.blockingContext)
            .bindHttp(port, "localhost")
            .withHttpApp(f(ep))
            .resource
          client <- BlazeClientBuilder[F](blocker.blockingContext).resource
        } yield (client, completer))
          .use {
            case (client, completer) =>
              for {
                _ <- client.expect[String](s"http://localhost:$port").attempt
                spans <- completer.get
                _ <- timer.sleep(100.millis)
              } yield fa(spans)
          }
      )

    test(injectRoutes(routes, _).orNotFound)
    test(injectApp(app, _))
  }

}