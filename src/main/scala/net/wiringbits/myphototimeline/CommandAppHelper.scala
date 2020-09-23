package net.wiringbits.myphototimeline

import java.nio.file.Paths

import cats.Traverse
import cats.data.Validated.{Invalid, Valid}
import cats.data.ValidatedNec
import cats.effect.{ExitCode, Sync}
import cats.syntax.all._
import com.drew.imaging.ImageMetadataReader
import com.monovore.decline.Opts

import scala.jdk.CollectionConverters.IterableHasAsScala

object CommandAppHelper {

  val appName: String = BuildInfo.name

  val appDescription: String = {
    s"""
      |My Photo Timeline
      |
      |Version: ${BuildInfo.version}
      |Git head: ${BuildInfo.gitHeadCommit.getOrElse("none")}
      |Bug reports: https://github.com/wiringbits/my-photo-timeline/issues
      |
      |You can collect all your photo directories in a single root directory, and just run the app (--dry-run doesn't alter your file system, it's recommended to try that first):
      |- ./my-photo-timeline --source ~/Desktop/test-photos --output ~/Desktop/test-output --dry-run
      |
      |The `test-photos` directory could look like:
      |
      |test-photos
      |├── img1.jpg
      |├── img1-again.jpg
      |├── invalid
      |│   ├── img2-no-metadata.jpg
      |├── img3.jpg
      |├── img4.jpg
      |├── img5.jpg
      |
      |Producing the `test-output` directory like:
      |
      |test-output
      |├── duplicated
      |│   ├── img1-again.jpg
      |├── invalid
      |│   ├── img2-no-metadata.jpg
      |└── organized
      |    ├── 2009
      |    │   └── 03-march
      |    │       ├── img1.jpg
      |    ├── 2010
      |    │   ├── 07-july
      |    │   │   ├── img3.jpg
      |    │   │   └── img4.jpg
      |    │   ├── 09-september
      |    │   │   ├── img5.jpg
      |
      |Where:
      |- test-output/duplicated has the photos were detected as duplicated.
      |- test-output/invalid has the photos (or non-photos) where the app couldn't detect the creation date.
      |- test-output/organized has the photos organized by date, the format being `year/month/photo-name`.
      |""".stripMargin
  }

  private def toDirPath[F[_]: Sync](pathStr: String, errMessage: String): F[ValidatedNec[String, String]] =
    Sync[F]
      .delay(os.Path(Paths.get(pathStr)))
      .map(path =>
        if (os.isDir(path)) path.toString.validNec[String]
        else errMessage.invalidNec[String])

  def sourceOpt[F[_]: Sync]: Opts[F[ValidatedNec[String, String]]] =
    Opts
      .option[String](
        long = "source",
        help = "The root directory to pick the photos to organize recursively (absolute path)."
      )
      .map(pathStr =>
        // for now, only absolute paths
        toDirPath(pathStr, "source: It's not a directory, an absolute path is required"))

  def outputOpt[F[_]: Sync]: Opts[F[ValidatedNec[String, String]]] =
    Opts
      .option[String](
        long = "output",
        help = "The root directory to place the organized photos (absolute path)"
      )
      .map(pathStr =>
        // for now, only absolute paths
        toDirPath(pathStr, "output: It's not a directory, an absolute path is required"))

  def run[F[_]: Sync](
      sourceF: F[ValidatedNec[String, String]],
      outputF: F[ValidatedNec[String, String]],
      dryRun: Boolean): F[ExitCode] =
    for {
      sourceV <- sourceF
      outputV <- outputF
      args <- Sync[F].delay((sourceV, outputV).mapN((sourcePath, outputPath) =>
        FileOrganizerTask.Arguments(inputRoot = sourcePath, outputBaseRoot = outputPath, dryRun = dryRun)))
      result <- new FileOrganizerTask[F](new SimpleLogger[F]).run(args).map {
        case Invalid(e) => ExitCode.Error
        case _ => ExitCode.Success
      }
    } yield result

  def findPotentialDate[F[_]: Sync](sourceFile: os.Path): F[Set[String]] = {
    for {
      metadata <- Sync[F].delay(ImageMetadataReader.readMetadata(sourceFile.toIO))
      tags <- Sync[F].delay(metadata.getDirectories.asScala.flatMap(
        _.getTags.asScala
          .filterNot(t => MetadataCreatedOnTag.names.contains(t.getTagName.toLowerCase))
          .filter(_.getTagName.toLowerCase.contains("date"))
          .map(_.getTagName)
      ).toSet)
    } yield tags
  }
}
