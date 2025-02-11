package io.janstenpickle.trace4cats.example

import cats.data.Kleisli
import cats.effect.{Concurrent, ExitCode, IO, IOApp, Resource}
import io.janstenpickle.trace4cats.Span
import io.janstenpickle.trace4cats.example.Fs2Example.entryPoint
import io.janstenpickle.trace4cats.http4s.client.syntax._
import io.janstenpickle.trace4cats.http4s.common.Http4sRequestFilter
import io.janstenpickle.trace4cats.http4s.server.syntax._
import io.janstenpickle.trace4cats.model.TraceProcess
import org.http4s.HttpRoutes
import org.http4s.client.Client
import org.http4s.client.blaze.BlazeClientBuilder
import org.http4s.dsl.Http4sDsl
import org.http4s.implicits._
import org.http4s.server.blaze.BlazeServerBuilder

object Http4sExample extends IOApp {

  def makeRoutes[F[_]: Concurrent](client: Client[Kleisli[F, Span[F], *]]): HttpRoutes[Kleisli[F, Span[F], *]] = {
    object dsl extends Http4sDsl[Kleisli[F, Span[F], *]]
    import dsl._

    HttpRoutes.of { case req @ GET -> Root / "forward" =>
      client.expect[String](req).flatMap(Ok(_))
    }
  }

  override def run(args: List[String]): IO[ExitCode] =
    (for {
      ep <- entryPoint[IO](TraceProcess("trace4catsHttp4s"))
      ec <- Resource.eval(IO.executionContext)
      client <- BlazeClientBuilder[IO](ec).resource

      routes = makeRoutes[IO](client.liftTrace()) // use implicit syntax to lift http client to the trace context

      server <-
        BlazeServerBuilder[IO](ec)
          .bindHttp(8080, "0.0.0.0")
          .withHttpApp(
            routes.inject(ep, requestFilter = Http4sRequestFilter.kubernetesPrometheus).orNotFound
          ) // use implicit syntax to inject an entry point to http routes
          .resource
    } yield server).use { _ =>
      IO(ExitCode.Success)
    }
}
