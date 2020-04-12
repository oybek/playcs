package io.github.oybek

import java.sql.Timestamp

import cats.effect.concurrent.Ref
import cats.effect.{Async, Concurrent, Sync, Timer}
import cats.syntax.all._
import cats.instances.option._
import io.github.oybek.config.Config
import io.github.oybek.domain.CmdStartCSDS
import io.github.oybek.service.Octopus
import org.slf4j.{Logger, LoggerFactory}
import telegramium.bots.client.Api
import telegramium.bots.high.LongPollBot

class TgBot[F[_]: Async: Timer: Concurrent](config: Config, ref: Ref[F, Option[Octopus[F]]])(implicit bot: Api[F])
  extends LongPollBot[F](bot) with TgExtractors {

  val log: Logger = LoggerFactory.getLogger("TgGate")

  import telegramium.bots._
  import telegramium.bots.client._

  override def onMessage(message: Message): F[Unit] =
    Sync[F].delay { log.info(s"got message: $message") } *> (message match {
      case Location(location) =>
        Sync[F].delay {
          log.debug(s"got location $location")
        }

      case Text(`/new`(map, _)) =>
        for {
          octopusOpt <- ref.get
          hldsDir = new java.io.File(config.hldsDir)
          _ <- octopusOpt.traverse(_.destroy)
          octopus <- Octopus.run(CmdStartCSDS(hldsDir)(map, 27015))
          _ <- ref.update(_ => Some(octopus))
          _ <- sendMessage(message.chat.id,
            s"""
               |Server created
               |- map: $map
               |
               |connect ${config.serverIp}:27015
               |""".stripMargin)
        } yield ()

      case Text("/kill") =>
        ref.get.flatMap(_.traverse(_.destroy)) *> ref.update(_ => None)

      case Text(text) =>
        Sync[F].delay {
          log.debug(s"got text $text")
        } *> sendMessage(message.chat.id, "Unknown command")

      case _ =>
        Sync[F].unit
    })

  private def sendMessage(chatId: Int, text: String): F[Unit] = {
    val sendMessageReq = SendMessageReq(chatId = ChatIntId(chatId), text = text)
    bot.sendMessage(sendMessageReq).void *>
      Sync[F].delay { log.info(s"send message: $sendMessageReq") }
  }

}
