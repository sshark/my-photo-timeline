package net.wiringbits.myphototimeline

import cats.data._
import cats.syntax.all._
import cats.data.Validated.{Invalid, Valid}
import cats.data.ValidatedNec
import cats.effect.Sync

object FileOrganizerTask {
  case class Arguments(inputRoot: String, outputBaseRoot: String, dryRun: Boolean) {
    val outputRoot: String = outputBaseRoot + "/organized"
    val duplicatedRoot: String = outputBaseRoot + "/duplicated"
    val invalidRoot: String = outputBaseRoot + "/invalid"

    val dataDirectories: List[String] = List(
      inputRoot,
      outputRoot,
      duplicatedRoot,
      invalidRoot
    )
  }
}

class FileOrganizerTask[F[_]: Sync](logger: SimpleLogger[F]) {

  import FileOrganizerTask._

  def run(args: ValidatedNec[String, Arguments]): F[ValidatedNec[String, Unit]] =
    args match {
      case err @ Invalid(e) => Sync[F].point(err)
      case Valid(a) => createNewValidDirs(a).flatMap(_ => run(a))
    }

  def run(args: Arguments): F[ValidatedNec[String, Unit]] = {
    for {
      _ <- logger.info("Loading already processed files, it may take some minutes, be patient")
      loadedOutputRoot <- FileOrganizerService.load(args.outputRoot)(trackProgress)
      (processedFiles, invalidProcessedFiles) = loadedOutputRoot
      _ <- logger.info(s"Already processed files loaded: ${processedFiles.size}")
      _ <- if (invalidProcessedFiles.nonEmpty)
        logger.warn(
          s"There are ${invalidProcessedFiles.size} files on the output folder without enough metadata to process, which you need to organize manually"
        )
      else Sync[F].unit
      _ <- logger.info("Loading files to process, it may take some minutes, be patient")
      loadedInputRoot <- FileOrganizerService.load(args.inputRoot)(trackProgress)
      (filesToProcess, invalidFilesToProcess) = loadedInputRoot
      _ <- logger.info(s"Files to process loaded: ${filesToProcess.size}")
      _ <- if (invalidFilesToProcess.nonEmpty)
        logger.warn(
          s"There are ${invalidFilesToProcess.size} files on the input folder without enough metadata to process"
        )
      else Sync[F].unit
      _ <- logger.info(s"Indexing now... it may take some minutes, be patient")
      allFiles = filesToProcess.data.keys.foldLeft(processedFiles) {
        case (acc, currentHash) =>
          acc + filesToProcess.data.getOrElse(currentHash, List.empty)
      }
      (newDuplicated, newUnique) = filesToProcess.data.values
        .foldLeft(List.empty[FileDetails] -> List.empty[FileDetails]) {
          case ((newDuplicated, newUnique), items) =>
            items.headOption
              .filter(fileDetails => processedFiles.contains(fileDetails.hash))
              .map(head =>
                // current batch has a new element, pick the first one
                (items.drop(1) ::: newDuplicated, head :: newUnique))
              .getOrElse(
                // current batch repeated
                (items ::: newDuplicated, newUnique)
              )
        }
      _ <- logger.info(s"""Initial indexing done
                          |- Unique files: ${allFiles.size}
                          |- Already organized files: ${processedFiles.size}
                          |- New duplicated files: ${newDuplicated.size}
                          |- New unique files to organize: ${newUnique.size}""".stripMargin)
      _ <- if (args.dryRun)
        logger.info("""Files not affected because dry-run is enabled
                      |Remember to remove the --dry-run option to actually organize the photos""".stripMargin)
      else {
        // Move duplicated files
        logger.info(s"Moving duplicated files to: ${args.duplicatedRoot}") *>
          organizeFiles(
            args,
            newDuplicated,
            (args, fileDetails, current, total) => moveFiles(args, fileDetails, current, total)) *>
          logger.info(s"Moving invalid files to: ${args.invalidRoot}") *>
          organizeFiles(
            args,
            invalidFilesToProcess,
            (args, fileDetails, current, total) => moveFiles(args, fileDetails, current, total)) *>
          logger.info(s"Organizing unique files to: ${args.outputRoot}") *>
          organizeFiles(
            args,
            newUnique,
            (args, fileDetails, current, total) => moveFiles(args, fileDetails, current, total)) *>
          logger.info("Cleaning up empty directories") *>
          fs2.Stream
            .eval(Sync[F].delay(List(os.Path(args.inputRoot), os.Path(args.outputRoot))))
            .flatMap(fs2.Stream.apply)
            .flatMap(path => fs2.Stream.eval(FileOrganizerService.cleanEmptyDirectories(path)))
            .compile
            .drain
      }
      _ <- logger.info(
        """Done.
         |I hope you found the app useful.
         |When I was looking for one, I was willing to pay $100 USD for it but found nothing fulfilling my needs.
         |any donations are welcome:
         |- Bitcoin: bc1qf37j0wutmn9ngkpn8v7mknukn3f0cmvq3p7dzf
         |- Ethereum: 0x02D1f6b4992fD147F19525150b97509D2eaAa651
         |- Litecoin: LWYPqEYG6fQdvCWCKWvFygskNTptqxuUHu
         |""".stripMargin)
    } yield ().validNec
  }

  private def moveFiles(args: Arguments, file: FileInfo, current: Int, total: Int): F[Unit] =
    trackProgress(current, total) *>
      (file match {
        case fp @ PathOnly(source) => FileOrganizerService.safeMove(os.Path(args.duplicatedRoot), fp)
        case FileDetails(source, createdOn, _) =>
          FileOrganizerService.organizeByDate(
            os.Path(args.outputRoot),
            source,
            createdOn
          )
      })

  private def organizeFiles(
      args: Arguments,
      fileDetailsList: List[FileInfo],
      f: (Arguments, FileInfo, Int, Int) => F[Unit]): F[Unit] =
    fs2.Stream
      .emits(fileDetailsList.zipWithIndex)
      .flatMap {
        case (file, index) =>
          fs2.Stream.eval(f(args, file, index, fileDetailsList.size))
      }
      .compile
      .drain

  private def trackProgress(current: Int, total: Int): F[Unit] =
    Option(percentage(current, total))
      .filter(_ > 0)
      .filter { currentPercent =>
        val previous = percentage(current - 1, total)
        currentPercent > previous && currentPercent % 5 == 0
      }
      .fold(Sync[F].unit)(currentPercent => logger.info(fansi.Color.Blue(s"Progress: $currentPercent%").render))

  private def percentage(x: Int, total: Int): Int = {
    (100 * (x * 1.0 / total)).toInt
  }

  private def createDir(pathStr: String): F[ValidatedNec[String, Boolean]] =
    for {
      osPath <- Sync[F].delay(os.Path(pathStr))
      exists <- Sync[F].delay(os.exists(osPath))
      isDir <- Sync[F].delay(os.isDir(osPath))
      isCreated <- if (isDir) {
        Sync[F].delay(false.validNec[String])
      } else if (!exists) {
        Sync[F].delay(os.makeDir.all(osPath)).flatMap(_ => Sync[F].delay(true.validNec[String]))
      } else {
        Sync[F].delay(s"$pathStr is not a directory, or it can't be created".invalidNec[Boolean])
      }
    } yield isCreated

  private def createNewValidDirs(args: Arguments): F[ValidatedNec[String, Arguments]] =
    for {
      _ <- if (args.outputRoot.startsWith(args.inputRoot))
        Sync[F].delay(s"The output directory can't be inside the input directory".invalidNec[Arguments])
      else Sync[F].delay(args.validNec[String])
      _ <- if (args.inputRoot.startsWith(args.outputRoot))
        Sync[F].delay("The input directory can't be inside the output directory".invalidNec[Arguments])
      else Sync[F].delay(args.validNec[String])
      outRootCreated <- createDir(args.outputRoot)
      dupRootCreated <- createDir(args.duplicatedRoot)
      invalidRootCreated <- createDir(args.invalidRoot)
    } yield (outRootCreated, dupRootCreated, invalidRootCreated).mapN((_, _, _) => args)
}
