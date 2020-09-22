package net.wiringbits.myphototimeline

import cats.effect.{ExitCode, IO}
import cats.implicits._
import com.monovore.decline._
import com.monovore.decline.effect.CommandIOApp
import net.wiringbits.myphototimeline.CommandAppHelper._

object Main extends CommandIOApp(name = appName, header = appDescription) {
  override def main: Opts[IO[ExitCode]] = {
    val dryRunOpt = Opts
      .flag("dry-run", help = "Print the actions to do without changing anything (recommended).")
      .orFalse

    (sourceOpt[IO], outputOpt[IO], dryRunOpt).mapN { (sourceF, outputF, dryRun) =>
      CommandAppHelper.run[IO](sourceF = sourceF, outputF = outputF, dryRun = dryRun)
    }
  }
}
