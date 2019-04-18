package com.lightbend.statefulserverless

import akka.actor.ActorSystem
import akka.cluster.Cluster
import akka.util.Timeout
import akka.stream.ActorMaterializer
import akka.http.scaladsl.{Http, HttpConnectionContext, UseHttp2}
import akka.http.scaladsl.Http.ServerBinding
import akka.cluster.sharding._
import akka.management.cluster.bootstrap.ClusterBootstrap
import akka.management.scaladsl.AkkaManagement
import akka.grpc.GrpcClientSettings
import com.typesafe.config.Config
import com.lightbend.statefulserverless.grpc._
import com.google.protobuf.empty.Empty

import scala.concurrent.{Await, Future, Promise}
import scala.concurrent.duration._
import scala.util.{Failure, Success}

object StatefulServerlessServer {
  private final case class Configuration private (
    devMode: Boolean,
    httpInterface: String,
    httpPort: Int,
    userFunctionInterface: String,
    userFunctionPort: Int,
    relayTimeout: Timeout,
    relayOutputBufferSize: Int,
    passivationTimeout: Timeout,
    numberOfShards: Int,
    proxyParallelism: Int) {
      def this(config: Config) = {
        this(
          devMode               = config.getBoolean("dev-mode-enabled"),
          httpInterface         = config.getString("http-interface"),
          httpPort              = config.getInt("http-port"),
          userFunctionInterface = config.getString("user-function-interface"),
          userFunctionPort      = config.getInt("user-function-port"),
          relayTimeout          = Timeout(config.getDuration("relay-timeout").toMillis.millis),
          relayOutputBufferSize = config.getInt("relay-buffer-size"),
          passivationTimeout    = Timeout(config.getDuration("passivation-timeout").toMillis.millis),
          numberOfShards        = config.getInt("number-of-shards"),
          proxyParallelism      = config.getInt("proxy-parallelism")
        )
        validate()
      }

      private[this] def validate(): Unit = {
        require(proxyParallelism > 0)
        // TODO add more config validation here
      }
    }

  def main(args: Array[String]): Unit = {
    implicit val system = ActorSystem("statefulserverless-backend")
    implicit val materializer = ActorMaterializer()
    implicit val executionContext = system.dispatcher

    // FIXME go over and supply appropriate values for Cluster Sharding
    // https://doc.akka.io/docs/akka/current/cluster-sharding.html?language=scala#configuration
    val config = new Configuration(system.settings.config.getConfig("stateful-serverless"))

    scala.sys.addShutdownHook { Await.ready(system.terminate(), 30.seconds) } // TODO make timeout configurable

    val clientSettings     = GrpcClientSettings.connectToServiceAt(config.userFunctionInterface, config.userFunctionPort).withTls(false)
    val client             = EntityClient(clientSettings) // FIXME configure some sort of retries?
    val cluster            = Cluster(system)

    // Bootstrap the cluster
    if (config.devMode) {
      // In development, we just have a cluster of one, so we join ourself.
      cluster.join(cluster.selfAddress)
    } else {
      AkkaManagement(system).start()
      ClusterBootstrap(system).start()
    }

    // FIXME introduce some kind of retry policy here
    client.ready(Empty.of()).flatMap({ reply =>

      val promise = Promise[ServerBinding]()

      cluster.registerOnMemberUp {

        promise.completeWith(Future({
          val stateManagerConfig = StateManager.Configuration(reply.persistenceId, config.passivationTimeout, config.relayOutputBufferSize)

          val stateManager = ClusterSharding(system).start(
            typeName = reply.persistenceId, // FIXME derive name from the actual proxied service?
            entityProps = StateManagerSupervisor.props(client, stateManagerConfig), // FIXME investigate dispatcher config
            settings = ClusterShardingSettings(system),
            messageExtractor = new Serve.CommandMessageExtractor(config.numberOfShards))

          Serve.createRoute(stateManager, config.proxyParallelism, config.relayTimeout, reply)
        }).flatMap({ route =>
          Http().bindAndHandleAsync(
            route,
            interface = config.httpInterface,
            port = config.httpPort,
            connectionContext = HttpConnectionContext(http2 = UseHttp2.Always)
          )
        }))
      }

      promise.future

    }).transform(Success(_)).foreach {
      case Success(ServerBinding(localAddress)) =>
        println(s"StatefulServerless backend online at $localAddress")
      case Failure(t) =>
        t.printStackTrace()
        // FIXME figure out what the cleanest exist looks like
        materializer.shutdown()
        system.terminate().andThen({
          case _ => scala.sys.exit(1)
        })
    }
  }
}