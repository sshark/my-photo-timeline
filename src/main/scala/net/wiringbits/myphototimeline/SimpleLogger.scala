package net.wiringbits.myphototimeline

import cats.effect.Sync
import cats.syntax.all._

class SimpleLogger[F[_]: Sync] {

  def info(msg: String): F[Unit] =
    Sync[F].delay(System.err.println(msg))

  def warn(msg: String): F[Unit] =
    Sync[F].delay(System.err.println(s"${fansi.Color.Yellow("WARNING")}: $msg"))

  def fatal(msg: String): F[Unit] =
    Sync[F].delay(System.err.println(s"${fansi.Color.Red("FATAL")}: $msg")) *>
      Sync[F].delay(
        System.err.println(
          "Please report this problem so that it gets fixed: https://github.com/wiringbits/my-photo-timeline/issues"))
}
