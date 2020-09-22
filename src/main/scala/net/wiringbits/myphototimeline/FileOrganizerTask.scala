package net.wiringbits.myphototimeline

import cats.data.Validated.{Invalid, Valid}
import cats.data.ValidatedNec
import cats.effect.Sync
import cats.syntax.all._

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

class FileOrganizerTask[F[_]: Sync](logger: SimpleLogger) {

  import FileOrganizerTask._

  def run(args: ValidatedNec[String, Arguments]): F[ValidatedNec[String, Unit]] =
    args match {
      case err @ Invalid(e) => Sync[F].point(err)
      case Valid(a) => createNewValidDirs(a).flatMap(_ => run(a))
    }

  def run(args: Arguments): F[ValidatedNec[String, Unit]] = {
    for {
      _ <- Sync[F].delay(logger.info("Loading already processed files, it may take some minutes, be patient"))
      loadedOutputRoot <- FileOrganizerService.load(args.outputRoot)(trackProgress)
      (processedFiles, invalidProcessedFiles) = loadedOutputRoot
      _ <- Sync[F].delay(logger.info(s"Already processed files loaded: ${processedFiles.size}"))
      _ <- if (invalidProcessedFiles.isEmpty)
        Sync[F].delay(
          logger.warn(
            s"There are ${invalidProcessedFiles.size} files on the output folder without enough metadata to process, which you need to organize manually"
          ))
      else Sync[F].unit
      _ <- Sync[F].delay(logger.info("Loading files to process, it may take some minutes, be patient"))
      loadedInputRoot <- FileOrganizerService.load(args.inputRoot)(trackProgress)
      (filesToProcess, invalidFilesToProcess) = loadedInputRoot
      _ <- Sync[F].delay(logger.info(s"Files to process loaded: ${filesToProcess.size}"))
      _ <- if (invalidFilesToProcess.nonEmpty)
        Sync[F].delay(
          logger.warn(
            s"There are ${invalidFilesToProcess.size} files on the input folder without enough metadata to process"
          ))
      else Sync[F].unit
      _ <- Sync[F].delay(logger.info(s"Indexing now... it may take some minutes, be patient"))
      allFiles = filesToProcess.data.keys.foldLeft(processedFiles) {
        case (acc, currentHash) =>
          acc + filesToProcess.data.getOrElse(currentHash, List.empty)
      }
      (newDuplicated, newUnique) = filesToProcess.data.values
        .foldLeft(List.empty[FileDetails] -> List.empty[FileDetails]) {
          case ((newDuplicated, newUnique), items) =>
            items.headOption
              .filterNot(f => processedFiles.contains(f.hash))
              .map { head =>
                // current batch has a new element, pick the first one
                (items.drop(1) ::: newDuplicated, head :: newUnique)
              }
              .getOrElse {
                // current batch repeated
                (items ::: newDuplicated, newUnique)
              }
        }
      _ <- Sync[F].delay(logger.info("Initial indexing done"))
      _ <- Sync[F].delay(logger.info(s"- Unique files: ${allFiles.size}"))
      _ <- Sync[F].delay(logger.info(s"- Already organized files: ${processedFiles.size}"))
      _ <- Sync[F].delay(logger.info(s"- New duplicated files: ${newDuplicated.size}"))
      _ <- Sync[F].delay(logger.info(s"- New unique files to organize: ${newUnique.size}"))
      _ <- if (args.dryRun)
        Sync[F].delay(logger.info("Files not affected because dry-run is enabled")) *> Sync[F].delay(
          logger.info("Remember to remove the --dry-run option to actually organize the photos"))
      else {
        // Move duplicated files
        Sync[F].delay(logger.info(s"Moving duplicated files to: ${args.duplicatedRoot}")) *>
          Sync[F].delay(newDuplicated.zipWithIndex.foreach {
            case (file, index) =>
              trackProgress(current = index, total = newDuplicated.size)
              FileOrganizerService
                .safeMove(destinationDirectory = os.Path(args.duplicatedRoot), sourceFile = file.source)
          }) *> Sync[F].delay(logger.info(s"Moving invalid files to: ${args.invalidRoot}")) *>
          Sync[F].delay(invalidFilesToProcess.zipWithIndex.foreach {
            case (file, index) =>
              trackProgress(current = index, total = invalidFilesToProcess.size)
              FileOrganizerService.safeMove(destinationDirectory = os.Path(args.invalidRoot), sourceFile = file)
          }) *> Sync[F].delay(logger.info(s"Organizing unique files to: ${args.outputRoot}")) *>
          Sync[F].delay(newUnique.zipWithIndex.foreach {
            case (file, index) =>
              trackProgress(current = index, total = newDuplicated.size)
              FileOrganizerService.organizeByDate(
                destinationDirectory = os.Path(args.outputRoot),
                sourceFile = file.source,
                createdOn = file.createdOn
              )
          }) *> Sync[F].delay(logger.info("Cleaning up empty directories")) *>
          Sync[F].delay(FileOrganizerService.cleanEmptyDirectories(os.Path(args.inputRoot))) *>
          Sync[F].delay(FileOrganizerService.cleanEmptyDirectories(os.Path(args.outputRoot)))
      }
      _ <- Sync[F].delay(logger.info("Done"))
      _ <- Sync[F]
        .pure("""
            |I hope you found the app useful.
            |
            |When I was looking for one, I was willing to pay $100 USD for it but found nothing fulfilling my needs.
            |any donations are welcome:
            |- Bitcoin: bc1qf37j0wutmn9ngkpn8v7mknukn3f0cmvq3p7dzf
            |- Ethereum: 0x02D1f6b4992fD147F19525150b97509D2eaAa651
            |- Litecoin: LWYPqEYG6fQdvCWCKWvFygskNTptqxuUHu
            |""".stripMargin)
        .map(text => logger.info(text))
    } yield ().validNec
  }

  private def trackProgress(current: Int, total: Int): F[Unit] = Sync[F].delay{
    def percent(x: Int): Int = {
      (100 * (x * 1.0 / total)).toInt
    }

    if (current > 0) {
      val currentPercent = percent(current)
      val previous = percent(current - 1)
      if (currentPercent > previous && currentPercent % 5 == 0) {
        logger.info(fansi.Color.Blue(s"Progress: $currentPercent%").render)
      }
    }
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
